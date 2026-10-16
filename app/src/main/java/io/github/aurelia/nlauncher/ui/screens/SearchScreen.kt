package io.github.aurelia.nlauncher.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
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
import io.github.aurelia.nlauncher.AppCatalog
import io.github.aurelia.nlauncher.LauncherActivity
import io.github.aurelia.nlauncher.R
import io.github.aurelia.nlauncher.ui.APP_LABEL_TEXT_SIZE
import io.github.aurelia.nlauncher.ui.ICON_TEXT_SCALE
import io.github.aurelia.nlauncher.ui.Screen
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
) : Screen {
    override val key = KEY

    override val view: View = activity.layoutInflater.inflate(R.layout.screen_search, null, false)

    /**
     * Called with the app the user picked. Set by whoever pushes the screen; a search
     * that was restored with the stack has nobody left to report to.
     */
    var onPicked: (Intent) -> Unit = {}

    private val searchInput: EditText = view.findViewById(R.id.search_input)
    private val listContainer: FrameLayout = view.findViewById(R.id.list_container)
    private val letterBar = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }

    /** Every index cell, built once; the filter hides the ones it has no results for. */
    private val letterCells = mutableMapOf<Char, TextView>()
    private val letterColumns = mutableListOf<LinearLayout>()
    private var letterCellSize = 0
    private var highlighted: Char? = null

    private var adapter: Adapter? = null
    private var listView: ListView? = null

    init {
        // The list is usually already resolved (the activity asks for it when it
        // starts); if it is not, build as soon as it arrives. Icons keep streaming
        // in either way and refresh the rows once they have all landed.
        AppCatalog.apps(
            activity,
            onReady = { apps -> show(apps) },
            onIconsLoaded = { adapter?.notifyDataSetChanged() },
        )
    }

    /** Draw the results, or explain that there is nothing to pick. */
    private fun show(apps: List<AppCatalog.AppEntry>) {
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
        // Every visit starts a fresh search. The input does not depend on the app
        // list, so it is set up even while a very fast first open is still waiting
        // for the list to arrive.
        searchInput.setText("")
        searchInput.requestFocus()
        searchInput.setSelection(0)
        view.post {
            // Searching is the whole point of this screen: bring the keyboard up.
            activity
                .getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(searchInput, 0)
            // The list container is measured only once this screen is laid out, so the
            // index cells can only be sized against the real height from here on.
            sizeLetterCells()
        }
    }

    override fun onDeactivated() {
        activity
            .getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun build(apps: List<AppCatalog.AppEntry>) {
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

        buildLetterBar()
        updateLetters()
        highlightVisibleLetter()

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
                    updateLetters()
                    highlightVisibleLetter()
                }

                override fun afterTextChanged(s: Editable?) {}
            },
        )

        // A very fast open can beat the list in: the keyboard is already up and the
        // box may already hold a query, so apply what is typed rather than showing
        // everything under it.
        if (searchInput.text.isNotEmpty()) {
            rows.filter(searchInput.text.toString())
            updateLetters()
            highlightVisibleLetter()
        }
        // If the screen has been laid out already (the list arrived after the push),
        // size the index now — the next activation is a long way off.
        sizeLetterCells()
    }

    /**
     * Every letter the index can show, built once in two columns against the top-left
     * corner. Filtering hides cells rather than rebuilding them, so typing stays
     * allocation free.
     */
    private fun buildLetterBar() {
        val perColumn = (ALL_LETTERS.size + 1) / 2
        val placeholder = (LETTER_CELL_PLACEHOLDER_DP * activity.resources.displayMetrics.density).roundToInt()
        letterCells.clear()
        letterColumns.clear()
        for (columnLetters in ALL_LETTERS.chunked(perColumn)) {
            val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            for (letter in columnLetters) {
                val cellView = TextView(activity)
                cellView.text = letter.uppercaseChar().toString()
                cellView.textSize = LETTER_TEXT_SIZE
                cellView.gravity = Gravity.CENTER
                cellView.setShadowLayer(3f, 1f, 1f, 0x99000000.toInt())
                cellView.setOnClickListener { jumpTo(letter) }
                cellView.visibility = View.GONE
                letterCells[letter] = cellView
                column.addView(cellView, LinearLayout.LayoutParams(placeholder, placeholder))
            }
            letterColumns += column
            letterBar.addView(column, LinearLayout.LayoutParams(placeholder, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /**
     * Give every cell an equal share of the available height. The worst case is a full
     * alphabet plus '#' (26 + 1), so that is what each cell is sized against: the index
     * keeps its proportions as the filter changes which letters are showing. Before the
     * first layout the height is unknown, so the cells keep their placeholder size until
     * [sizeLetterCells] can do better.
     */
    private fun sizeLetterCells() {
        // Nothing to size until the list has been built and with it the cells.
        if (letterCells.isEmpty()) return
        val available = listContainer.height - listContainer.paddingBottom
        if (available <= 0) return
        val cell = available / ALL_LETTERS.size
        if (cell == letterCellSize) return
        letterCellSize = cell
        for (cellView in letterCells.values) {
            cellView.layoutParams = LinearLayout.LayoutParams(cell, cell)
        }
        for (column in letterColumns) {
            column.layoutParams = LinearLayout.LayoutParams(cell, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Show the letters the current results actually have, and hide the rest. */
    private fun updateLetters() {
        val shown = adapter?.letters()?.toSet().orEmpty()
        for ((letter, cellView) in letterCells) {
            cellView.visibility = if (letter in shown) View.VISIBLE else View.GONE
        }
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
        val label = (adapter.getItem(position) as AppCatalog.AppEntry).label
        val letter = label.firstOrNull()?.lowercaseChar()?.takeIf { it.isLetter() } ?: '#'
        if (letter == highlighted) return
        highlighted = letter
        for ((cellLetter, cell) in letterCells) {
            val current = cellLetter == letter
            cell.typeface = if (current) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            cell.setTextColor(
                activity.getColor(if (current) R.color.text_primary else R.color.icon_tint_muted),
            )
        }
    }

    private fun jumpTo(letter: Char) {
        val position = adapter?.indexOfLetter(letter) ?: return
        if (position >= 0) listView?.setSelection(position)
    }

    /** Close this screen and hand the picked app to whoever opened the search. */
    private fun pick(intent: Intent) {
        activity.back()
        onPicked(intent)
    }

    private class Adapter(
        private val activity: Activity,
        private val all: List<AppCatalog.AppEntry>,
        private val onPick: (AppCatalog.AppEntry) -> Unit,
    ) : BaseAdapter() {
        private var shown: List<AppCatalog.AppEntry> = all

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
            // The icon may still be loading in the background; the list refreshes
            // itself once they have all landed.
            icon.setImageDrawable(AppCatalog.icon(activity, entry))
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

        /** Cell size (dp) before the screen has been laid out and can be measured. */
        private const val LETTER_CELL_PLACEHOLDER_DP = 22

        /** Every letter the index can show: '#' for non-letters, then the alphabet. */
        private val ALL_LETTERS: List<Char> = listOf('#') + ('a'..'z').toList()

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
