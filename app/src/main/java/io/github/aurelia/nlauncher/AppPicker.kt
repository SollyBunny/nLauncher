package io.github.aurelia.nlauncher

import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
 * The single "pick an app" screen. Both the gesture bindings and the home screen's
 * edit mode go through here, so there is exactly one implementation.
 *
 * It opens as a full-screen search: the search bar sits on top and is auto-focused,
 * the results fill the width next to the A-Z index, filtered as you type.
 * Like the home screen, the window is transparent with FLAG_SHOW_WALLPAPER, so the
 * live wallpaper stays visible (and interactive) behind it.
 */
object AppPicker {
    /** Text size (sp) of the A-Z index letters down the side of the picker. */
    private const val LETTER_TEXT_SIZE = 16f

    private class AppEntry(
        val label: String,
        val icon: Drawable,
        val intent: Intent,
    )

    /** The picker currently on screen, if any, so it can be dismissed on demand. */
    private var current: Dialog? = null

    // The app list (labels, icons and intents) is built once and kept in memory for
    // the life of the process. Callers never see this field: they ask for the list
    // through [apps], which loads it on first use and refreshes it on package changes.
    private var cachedApps: List<AppEntry>? = null
    private var watchingPackages = false

    fun show(
        activity: Activity,
        onPicked: (Intent) -> Unit,
        onDismiss: () -> Unit = {},
    ) {
        val apps = apps(activity)

        if (apps.isEmpty()) {
            Toast.makeText(activity, R.string.no_apps, Toast.LENGTH_SHORT).show()
            onDismiss()
            return
        }

        val dialog = Dialog(activity, R.style.Theme_Mlauncherish_Search)
        dialog.setContentView(R.layout.dialog_app_search)
        dialog.window?.let { window ->
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            // Same trick as the home screen: wallpaper behind, touch passthrough for live ones.
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        }

        val searchInput = dialog.findViewById<EditText>(R.id.search_input)
        val listContainer = dialog.findViewById<FrameLayout>(R.id.list_container)
        val adapter =
            Adapter(activity, apps) { entry ->
                dialog.dismiss()
                onPicked(entry.intent)
            }

        // Keep the search bar clear of the status bar. Applied to the whole bar
        // (icon + input) so they move down together.
        val searchBar = dialog.findViewById<View>(R.id.search_bar)
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

        // A-Z index: one tappable cell per available letter, laid out in two
        // columns against the top-left corner with no gaps between them. Tapping
        // a cell jumps the list to the first app starting with that letter.
        val letterBar = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            letterBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val listView = ListView(activity)
        listView.divider = null
        listView.dividerHeight = 0
        row.addView(
            listView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
        )
        listView.adapter = adapter

        fun rebuildLetterBar() {
            letterBar.removeAllViews()
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
                    cellView.setTextColor(activity.getColor(R.color.text_primary))
                    cellView.setShadowLayer(3f, 1f, 1f, 0x99000000.toInt())
                    cellView.setOnClickListener {
                        val position = adapter.indexOfLetter(letter)
                        if (position >= 0) listView.setSelection(position)
                    }
                    column.addView(cellView, LinearLayout.LayoutParams(cell, cell))
                }
                letterBar.addView(
                    column,
                    LinearLayout.LayoutParams(cell, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
        }
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
                    adapter.filter(s?.toString().orEmpty())
                    rebuildLetterBar()
                }

                override fun afterTextChanged(s: Editable?) {}
            },
        )

        dialog.setOnDismissListener {
            current = null
            onDismiss()
        }
        current = dialog
        searchInput.requestFocus()
        searchInput.setSelection(searchInput.text.length)
        dialog.show()
        // The container is measured only after the dialog is shown; rebuild once
        // more so the cells can be sized against the real height.
        listContainer.post { rebuildLetterBar() }
    }

    /** Dismiss the picker if one is open (e.g. when the Home button is pressed). */
    fun dismiss() {
        current?.dismiss()
    }

    /**
     * The launchable apps, in label order. Resolves the list on first use and then
     * keeps it in memory, invalidated whenever packages are installed, removed or
     * changed, so opening the picker stays cheap.
     */
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
}
