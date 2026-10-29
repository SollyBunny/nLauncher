package io.github.aurelia.nlauncher.ui.screens

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.provider.Settings
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.aurelia.nlauncher.AppCatalog
import io.github.aurelia.nlauncher.AppCatalogWatcher
import io.github.aurelia.nlauncher.AppEntry
import io.github.aurelia.nlauncher.AppUsage
import io.github.aurelia.nlauncher.Icons
import io.github.aurelia.nlauncher.LauncherActivity
import io.github.aurelia.nlauncher.LauncherConfig
import io.github.aurelia.nlauncher.R
import io.github.aurelia.nlauncher.ui.Screen
import io.github.aurelia.nlauncher.ui.padForSystemBars
import io.github.aurelia.nlauncher.ui.spToPx
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The "pick an app" screen. The gesture bindings, the favorites editor and the home
 * screen's search gesture all push it, so there is exactly one implementation.
 *
 * The search bar sits on top and is auto-focused, and the results fill the width next
 * to the A-Z index, filtered as you type. Tapping a result picks it; long-pressing
 * one opens that app's own settings page. Like the rest of the launcher it paints
 * nothing of its own, so the live wallpaper stays visible (and interactive) behind it.
 *
 * The screen makes room for the keyboard itself (the window will not), and the index
 * is one scrollable column, so neither the results nor the far end of the alphabet is
 * left behind it.
 */
class SearchScreen(
    private val activity: LauncherActivity,
) : Screen {
    override val key = KEY

    override val view: View = activity.layoutInflater.inflate(R.layout.screen_search, null, false)

    private val searchInput: EditText = view.findViewById(R.id.search_input)
    private val listContainer: FrameLayout = view.findViewById(R.id.list_container)
    private val letterBar =
        ScrollView(activity).apply {
            verticalScrollbarPosition = View.SCROLLBAR_POSITION_LEFT
        }

    /** Every index cell, built once; the filter hides the ones it has no results for. */
    private val letterCells = mutableMapOf<Char, TextView>()
    private var highlighted: Char? = null

    private var adapter: Adapter? = null
    private var listView: ListView? = null

    private var renderedRevision = -1
    private var renderedUsage = false
    private var renderedLabelSp = -1f
    private var renderedTextColor = 0
    private var active = false
    private var catalog: AppCatalog? = null

    /**
     * Whether the results are ordered by how often they have been picked while the
     * query is empty. The home screen's search gesture turns this on; every other
     * caller wants the plain alphabetical picker.
     */
    var sortByUsage = false

    init {
        AppCatalogWatcher.watchAppCatalog(activity) {
            catalog = it
            if (active) show()
        }

        // Keep the search bar clear of the status bar and the results clear of the
        // navigation bar and the keyboard, both on the root. Reading the insets the
        // window actually applied is what keeps this right: the bar's own size is not
        // always what the window reserved for it, and guessing leaves a gap above the
        // search bar.
        view.padForSystemBars(ime = true)
    }

    /** Draw the results, or explain that there is nothing to pick. */
    private fun show() {
        catalog?.let { catalog ->
            val labelSp = LauncherConfig.labelTextSp(activity)
            val textColor = LauncherConfig.textColor(activity)
            if (catalog.revision == renderedRevision &&
                sortByUsage == renderedUsage &&
                labelSp == renderedLabelSp &&
                textColor == renderedTextColor
            ) {
                return
            }
            val labelsResized = labelSp != renderedLabelSp
            renderedRevision = catalog.revision
            renderedUsage = sortByUsage
            renderedLabelSp = labelSp
            renderedTextColor = textColor
            if (catalog.apps.isEmpty()) {
                Toast.makeText(activity, R.string.no_apps, Toast.LENGTH_SHORT).show()
                view.post { if (active) activity.back() }
            } else {
                // The rows restyle themselves as they are drawn, but the index is built
                // once, so a new label size has to build it again.
                if (labelsResized && adapter != null) buildLetterBar()
                build(catalog.apps)
            }
        }
    }

    override fun onActivated() {
        active = true
        show()
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
        }
    }

    override fun onDeactivated() {
        active = false
        activity
            .getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun build(apps: List<AppEntry>) {
        val built = adapter
        if (built != null) {
            // The screen is reused: replace what the list holds instead of building
            // it again, so the rows, the index and the typed query all survive a
            // package change.
            built.update(apps, searchInput.text.toString(), sortByUsage)
            updateLetters()
            highlightVisibleLetter()
            return
        }
        val rows =
            Adapter(
                activity,
                apps,
                onAppSettings = { entry -> showAppSettings(entry) },
            )
        adapter = rows
        val results = ListView(activity)
        results.divider = null
        results.dividerHeight = 0
        results.adapter = rows
        listView = results

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
                ViewGroup.LayoutParams.MATCH_PARENT,
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
        // everything under it. With no query this is also what puts the usage order
        // in place the first time the screen is built.
        rows.update(apps, searchInput.text.toString(), sortByUsage)
        updateLetters()
        highlightVisibleLetter()
    }

    /**
     * Every letter the index can show, in the one column, built once. Filtering hides
     * cells rather than rebuilding them, so typing stays allocation free.
     *
     * The column scrolls, which is what keeps the whole alphabet reachable with the
     * keyboard up: one column of 26 letters plus '#' is taller than what is left of
     * the screen then, and shrinking the cells to fit would leave them unreadable.
     */
    private fun buildLetterBar() {
        letterBar.removeAllViews()
        letterCells.clear()
        // The cells are rebuilt whenever the size changes, so none of them keeps the
        // highlight from the previous build.
        highlighted = null
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val labelSp = LauncherConfig.labelTextSp(activity)
        for (letter in ALL_LETTERS) {
            val cellView = TextView(activity)
            cellView.text = letter.uppercaseChar().toString()
            cellView.textSize = labelSp
            cellView.gravity = Gravity.CENTER
            cellView.setShadowLayer(3f, 1f, 1f, 0x99000000.toInt())
            cellView.setOnClickListener { jumpTo(letter) }
            cellView.visibility = View.GONE
            letterCells[letter] = cellView

            val textHeight = cellView.paint.fontMetrics.let { it.descent - it.ascent }
            val cellSize = (textHeight * 1.5f).roundToInt()
            column.addView(cellView, LinearLayout.LayoutParams(cellSize, cellSize))
        }
        letterBar.addView(column)
    }

    /** Show the letters the current results actually have, and hide the rest. */
    private fun updateLetters() {
        // Jumping by letter makes no sense while the results are ordered by usage.
        letterBar.visibility = if (usageOrdered()) View.GONE else View.VISIBLE
        val shown = adapter?.letters()?.toSet().orEmpty()
        for ((letter, cellView) in letterCells) {
            cellView.visibility = if (letter in shown) View.VISIBLE else View.GONE
        }
    }

    /** Results ordered by how often they have been picked, or only when searching. */
    private fun usageOrdered(): Boolean = sortByUsage && searchInput.text.isBlank()

    /**
     * Mark the letter of the topmost visible app as the current one: bold and in the
     * primary color, while the other letters stay muted. Nothing happens while the
     * list is still within the same letter.
     */
    private fun highlightVisibleLetter() {
        val adapter = this.adapter ?: return
        val position = listView?.firstVisiblePosition ?: return
        if (position < 0 || position >= adapter.count) return
        val letter = letterOf(adapter.getItem(position) as AppEntry)
        if (letter == highlighted) return
        highlighted = letter
        for ((cellLetter, cell) in letterCells) {
            val current = cellLetter == letter
            cell.typeface = if (current) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            cell.setTextColor(
                activity.getColor(if (current) R.color.text_primary else R.color.icon_tint_muted),
            )
        }
        // Scroll the highlighted letter into the middle of the scrollable index column.
        val letterCell = letterCells[letter]
        if (letterCell != null) {
            val top = letterCell.top
            val bottom = letterCell.bottom
            letterBar.smoothScrollTo(0, top - letterBar.height / 2 + (bottom - top) / 2)
        }
    }

    private fun jumpTo(letter: Char) {
        val position = adapter?.indexOfLetter(letter) ?: return
        if (position >= 0) listView?.setSelection(position)
    }

    /**
     * Long-pressing a result opens that app's own settings page — the same "app
     * info" screen the system shows for a long press on a launcher icon.
     */
    private fun showAppSettings(entry: AppEntry) {
        val packageName = entry.intent.component?.packageName ?: return
        activity.launch(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName")),
        )
    }

    private class Adapter(
        private val activity: LauncherActivity,
        apps: List<AppEntry>,
        private val onAppSettings: (AppEntry) -> Unit,
    ) : BaseAdapter() {
        private var all: List<AppEntry> = apps
        private var shown: List<AppEntry> = all
        private var sortByUsage = false

        /** Replace the apps behind the current query, after a package change. */
        fun update(
            apps: List<AppEntry>,
            query: String,
            sortByUsage: Boolean,
        ) {
            all = apps
            this.sortByUsage = sortByUsage
            filter(query)
        }

        /** Distinct index labels in the results: '#' first, then a-z. */
        fun letters(): List<Char> {
            val groups = shown.map { letterOf(it) }.distinct()
            return if (NON_LETTER in groups) {
                listOf(NON_LETTER) + groups.filter { it != NON_LETTER }.sorted()
            } else {
                groups.sorted()
            }
        }

        /**
         * First shown position under [letter]: for '#' the first label that does not
         * start with an a-z character, otherwise the first under that letter.
         * -1 if there is none.
         */
        fun indexOfLetter(letter: Char): Int = shown.indexOfFirst { letterOf(it) == letter }

        fun filter(query: String) {
            val q = query.trim().lowercase(Locale.ROOT)
            shown =
                when {
                    q.isNotEmpty() -> all.filter { it.searchLabel.contains(q) }
                    sortByUsage -> {
                        // Most picked first; apps that were never picked stay in
                        // alphabetical order at the end.
                        val ranks = AppUsage.ranks(activity)
                        all.sortedWith(
                            compareBy<AppEntry> { ranks[it.key] ?: Int.MAX_VALUE }
                                .thenBy { it.searchLabel },
                        )
                    }
                    else -> all
                }
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
            // The row is recycled, so it has to say which app it is showing: an icon
            // that arrives after the row has moved on to another app must not land on
            // it. The same guard is what keeps this row honest while it scrolls.
            icon.tag = entry.key
            // Draw whatever is cached and let this row fill itself in when its own
            // icon lands, rather than leaving every row blank until the last icon in
            // the catalog has arrived.
            icon.setImageDrawable(
                Icons.icon(activity, entry.intent) { loaded ->
                    if (icon.tag == entry.key) icon.setImageDrawable(loaded)
                },
            )
            label.text = entry.label
            label.textSize = LauncherConfig.labelTextSp(activity)
            label.setTextColor(LauncherConfig.textColor(activity))

            // The row itself is clickable (see item_app_choice.xml), so it consumes
            // the touch and ListView's onItemClick never fires; handle the pick here.
            view.setOnClickListener {
                AppUsage.record(activity, entry.key)
                activity.searchCompleted(entry.intent)
            }
            // Long-press goes to the app's settings page instead, and returning true
            // keeps it from counting as a pick as well.
            view.setOnLongClickListener {
                onAppSettings(entry)
                true
            }

            // With icons turned off in Look and feel the row is just the label.
            icon.visibility = if (LauncherConfig.iconsShown(activity)) View.VISIBLE else View.GONE

            // Icons have a size of their own rather than following the label.
            val size = LauncherConfig.iconSizeSp(activity).spToPx(activity)
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

        /** Every letter the index can show: '#' for non-letters, then the alphabet. */
        private val ALL_LETTERS: List<Char> = listOf(NON_LETTER) + ('a'..'z').toList()
    }
}

/** The index letter for anything that does not start with one of the a-z letters. */
private const val NON_LETTER = '#'

/**
 * The index letter a label belongs under: its first character if that is one of the
 * twenty-six the index offers, and [#NON_LETTER] for everything else — labels starting
 * with a digit, a symbol, or a letter outside a-z.
 *
 * One definition for grouping, jumping and highlighting, so a label can never be
 * listed under one letter and highlighted under another.
 *
 * TODO: the index is a-z only. An app named "Écran" or "Über" is listed and reachable
 *  by scrolling or searching, but the index files it under '#' alongside digits and
 *  symbols. Grouping those properly means sorting with a [java.text.Collator] and
 *  showing the locale's own letters in the bar.
 */
private fun letterOf(entry: AppEntry): Char = entry.searchLabel.firstOrNull()?.takeIf { it in 'a'..'z' } ?: NON_LETTER
