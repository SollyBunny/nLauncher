package io.github.aurelia.nlauncher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * The "pick an app" screen. The gesture bindings, the favorites editor and the home
 * screen's search gesture all push it, so there is exactly one implementation.
 *
 * The search bar sits on top and is auto-focused, and the results fill the width next
 * to the A-Z index, filtered as you type. Like the rest of the launcher it paints
 * nothing of its own, so the live wallpaper stays visible (and interactive) behind it.
 */
class SearchScreen(
    private val activity: LauncherActivity,
    private val onPicked: (Intent) -> Unit,
) : Screen {
    override val key = KEY

    override val view: View = activity.layoutInflater.inflate(R.layout.screen_search, null, false)

    private val searchInput: EditText = view.findViewById(R.id.search_input)
    private val listContainer: FrameLayout = view.findViewById(R.id.list_container)
    private val letterBar = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }

    /** The index cells, so scrolling can restyle the one letter the list is at. */
    private val letterCells = mutableMapOf<Char, TextView>()
    private var highlighted: Char? = null

    private var adapter: Adapter? = null
    private var listView: ListView? = null

    init {
        val apps = apps(activity)
        if (apps.isEmpty()) {
            // Nothing to pick: say so, and leave this screen behind again as soon as
            // the stack has finished pushing it.
            Toast.makeText(activity, R.string.no_apps, Toast.LENGTH_SHORT).show()
            view.post { activity.back() }
        } else {
            build(apps)
        }
    }

    override fun onActivated() {
        if (adapter == null) return
        searchInput.requestFocus()
        searchInput.setSelection(searchInput.text.length)
        view.post {
            // Searching is the whole point of this screen: bring the keyboard up.
            activity.getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(searchInput, 0)
            // The list container is measured only once this screen is laid out; size
            // the index cells against the real height now that it is known.
            listContainer.post { rebuildLetterBar() }
        }
    }

    override fun onDeactivated() {
        activity.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun build(apps: List<AppEntry>) {
        // Keep the results clear of the navigation bar.
        listContainer.setPaddingRelative(0, 0, 0, navigationBarHeight(activity))

        val rows = Adapter(activity, apps) { entry -> pick(entry.intent) }
        adapter = rows
        val results = ListView(activity)
        results.divider = null
        results.dividerHeight = 0
        results.adapter = rows
        listView = results

        // Keep the search bar clear of the status bar. Applied to the whole bar
        // (icon + input) so they move down together.
        val searchBar = view.findViewById<View>(R.id.search_bar)
        searchBar.setPadding(
            searchBar.paddingLeft,
            searchBar.paddingTop + statusBarHeight(activity),
            searchBar.paddingRight,
            searchBar.paddingBottom,
        )

        // A-Z index on the left, results filling all the remaining width so the
        // app list gets as much room as the screen can spare.
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        listContainer.addView(
            row,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP or Gravity.START,
            ),
        )

        // A-Z index: one tappable cell per available letter, laid out in two columns
        // against the top-left corner with no gaps between them. Tapping a cell jumps
        // the list to the first app starting with that letter.
        row.addView(
            letterBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        row.addView(results, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        // The index follows the list: whichever letter the topmost visible app
        // belongs to is the highlighted one.
        results.setOnScrollChangeListener { _, _, _, _, _ -> highlightVisibleLetter() }

        rebuildLetterBar()

        searchInput.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                    rows.filter(s?.toString().orEmpty())
                    // A new query means a new result set: show it from the top.
                    listView?.setSelection(0)
                    rebuildLetterBar()
                }

                override fun afterTextChanged(s: Editable?) {}
            },
        )
    }

    private fun rebuildLetterBar() {
        val adapter = this.adapter ?: return
        letterBar.removeAllViews()
        letterCells.clear()
        val letters = adapter.letters()
        if (letters.isEmpty()) return
        val density = activity.resources.displayMetrics.density
        val preferred = (22 * density).roundToInt()
        // Give every cell an equal share of the available height, sized for the
        // worst case of a full alphabet plus '#' (26 + 1). Two columns then hold
        // the letters, so with a full alphabet each column takes roughly half the
        // available height. Using the maximum rather than the current letter count
        // keeps the cells a stable size as the filtered letter set changes. Before
        // the first layout the height is unknown, so fall back to the preferred
        // size (a follow-up rebuild runs once the container is measured).
        val available = listContainer.height - listContainer.paddingBottom
        val cell = if (available > 0) available / (26 + 1) else preferred
        // Fill the first column before the second so the letters stay in order
        // reading down, then across.
        val perColumn = (letters.size + 1) / 2
        val columns = listOf(letters.take(perColumn), letters.drop(perColumn))
        for (columnLetters in columns) {
            if (columnLetters.isEmpty()) continue
            val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            for (letter in columnLetters) {
                val cellView = TextView(activity)
                cellView.text = letter.uppercaseChar().toString()
                cellView.textSize = LETTER_TEXT_SIZE
                cellView.gravity = Gravity.CENTER
                cellView.setShadowLayer(3f, 1f, 1f, 0x99000000.toInt())
                cellView.setOnClickListener {
                    val position = adapter.indexOfLetter(letter)
                    if (position >= 0) listView?.setSelection(position)
                }
                letterCells[letter] = cellView
                column.addView(cellView, LinearLayout.LayoutParams(cell, cell))
            }
            letterBar.addView(
                column,
                LinearLayout.LayoutParams(cell, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        // The cells are new, so style them for where the list currently is.
        highlighted = null
        highlightVisibleLetter()
    }

    /**
     * Mark the letter of the topmost visible app as the current one: bold and in the
     * primary color, while the other letters stay muted. Nothing happens while the
     * list is still within the same letter.
     */
    private fun highlightVisibleLetter() {
        val adapter = this.adapter ?: return
        val position = listView?.firstVisiblePosition ?: return
        if (position < 0 || position >= adapter.count) return
        val label = (adapter.getItem(position) as AppEntry).label
        val letter = label.firstOrNull()?.lowercaseChar()?.takeIf { it.isLetter() } ?: '#'
        if (letter == highlighted) return
        highlighted = letter
        for ((cellLetter, cell) in letterCells) {
            val current = cellLetter == letter
            cell.setTypeface(if (current) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
            cell.setTextColor(
                activity.getColor(if (current) R.color.text_primary else R.color.icon_tint_muted),
            )
        }
    }

    /** Close this screen and hand the picked app to whoever opened the search. */
    private fun pick(intent: Intent) {
        activity.back()
        onPicked(intent)
    }

    private class AppEntry(
        val label: String,
        val icon: Drawable,
        val intent: Intent,
    )

    private class Adapter(
        private val activity: Activity,
        private val all: List<AppEntry>,
        private val onPick: (AppEntry) -> Unit,
    ) : BaseAdapter() {
        private var shown: List<AppEntry> = all

        /** Distinct index labels: '#' first (labels not starting with a letter), then a-z. */
        fun letters(): List<Char> {
            val alpha =
                shown
                    .mapNotNull { it.label.firstOrNull()?.lowercaseChar() }
                    .filter { it in 'a'..'z' }
                    .distinct()
                    .sorted()
            val hasNonLetter = shown.any { it.label.firstOrNull()?.isLetter() != true }
            return if (hasNonLetter) listOf('#') + alpha else alpha
        }

        /**
         * First shown position under [letter]: for '#' the first non-letter label,
         * otherwise the first label starting with that letter. -1 if absent.
         */
        fun indexOfLetter(letter: Char): Int =
            if (letter == '#') {
                shown.indexOfFirst { it.label.firstOrNull()?.isLetter() != true }
            } else {
                shown.indexOfFirst { it.label.lowercase().startsWith(letter) }
            }

        fun filter(query: String) {
            val q = query.trim().lowercase()
            shown = if (q.isEmpty()) all else all.filter { it.label.lowercase().contains(q) }
            notifyDataSetChanged()
        }

        override fun getCount(): Int = shown.size

        override fun getItem(position: Int): Any = shown[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(
            position: Int,
            convertView: View?,
            parent: ViewGroup,
        ): View {
            val view =
                convertView
                    ?: activity.layoutInflater.inflate(R.layout.item_app_choice, parent, false)
            val entry = shown[position]
            val icon = view.findViewById<ImageView>(R.id.app_choice_icon)
            val label = view.findViewById<TextView>(R.id.app_choice_label)
            icon.setImageDrawable(entry.icon)
            label.text = entry.label
            label.textSize = APP_LABEL_TEXT_SIZE

            // The row itself is clickable (see item_app_choice.xml), so it consumes
            // the touch and ListView's onItemClick never fires; handle the pick here.
            view.setOnClickListener { onPick(entry) }

            // Icons sit at ICON_TEXT_SCALE times the label's text size.
            val size = (label.textSize * ICON_TEXT_SCALE).roundToInt()
            val params = icon.layoutParams
            if (params.width != size || params.height != size) {
                params.width = size
                params.height = size
                icon.layoutParams = params
            }
            return view
        }
    }

    companion object {
        const val KEY = "search"

        /** Text size (sp) of the A-Z index letters down the side of the picker. */
        private const val LETTER_TEXT_SIZE = 16f

        // The app list (labels, icons and intents) is built once and kept in memory for
        // the life of the process: opening the search is then cheap, and the list is
        // refreshed whenever packages are installed, removed or changed.
        private var cachedApps: List<AppEntry>? = null
        private var watchingPackages = false

        private fun apps(context: Context): List<AppEntry> {
            cachedApps?.let { return it }
            val pm = context.packageManager
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val loaded =
                pm.queryIntentActivities(main, 0)
                    .asSequence()
                    .map { it.activityInfo }
                    // Keep the launcher's own home activity out of the picker list.
                    .filter { it.packageName != context.packageName }
                    .distinctBy { it.packageName to it.name }
                    .map {
                        AppEntry(
                            label = it.loadLabel(pm).toString(),
                            icon = it.loadIcon(pm),
                            intent =
                                Intent(Intent.ACTION_MAIN)
                                    .addCategory(Intent.CATEGORY_LAUNCHER)
                                    .setComponent(ComponentName(it.packageName, it.name)),
                        )
                    }.sortedBy { it.label.lowercase() }
                    .toList()
            cachedApps = loaded
            watchPackages(context.applicationContext)
            return loaded
        }

        /** Invalidate the cached app list when apps are installed, removed or changed. */
        private fun watchPackages(context: Context) {
            if (watchingPackages) return
            watchingPackages = true
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addAction(Intent.ACTION_PACKAGE_CHANGED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addDataScheme("package")
                }
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        cachedApps = null
                    }
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }

        private fun statusBarHeight(activity: Activity): Int {
            val res = activity.resources
            val id = res.getIdentifier("status_bar_height", "dimen", "android")
            return if (id > 0) res.getDimensionPixelSize(id) else 0
        }

        private fun navigationBarHeight(activity: Activity): Int {
            val res = activity.resources
            val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
            return if (id > 0) res.getDimensionPixelSize(id) else 0
        }
    }
}