package io.github.aurelia.nlauncher.ui.screens

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
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
import io.github.aurelia.nlauncher.ui.ShadowedIconDrawable
import io.github.aurelia.nlauncher.ui.dpToPx
import io.github.aurelia.nlauncher.ui.padForSystemBars
import io.github.aurelia.nlauncher.ui.spToPx
import java.util.Locale
import kotlin.math.roundToInt

class SearchScreen(
    private val activity: LauncherActivity,
) : Screen {
    override val key = KEY

    override val view: View = activity.layoutInflater.inflate(R.layout.screen_search, null, false)

    private val searchInput: EditText = view.findViewById(R.id.search_input)
    private val searchIcon: ImageView = view.findViewById(R.id.search_icon)
    private val listContainer: FrameLayout = view.findViewById(R.id.list_container)
    private val letterBar =
        ScrollView(activity).apply {
            verticalScrollbarPosition = View.SCROLLBAR_POSITION_LEFT
        }

    /** Every index cell, built once; the filter hides the ones it has no results for. */
    private val letterCells = mutableMapOf<Char, TextView>()

    /** The clock above the letters, which jumps back to the recent section. */
    private var recentCell: ImageView? = null
    private val recentCellIcon: Drawable =
        requireNotNull(activity.getDrawable(R.drawable.ic_recent)) {
            "Missing recent icon"
        }

    /** Whether the list is currently showing the recent section at its top. */
    private var recentHighlighted = false

    private var highlighted: Char? = null

    private var adapter: Adapter? = null
    private var listView: ListView? = null

    private var renderedRevision = -1
    private var renderedUsage = false
    private var renderedLabelSp = -1f
    private var renderedTextColor = 0
    private var active = false
    private var catalog: AppCatalog? = null

    /** The one cursor drawable, kept so recoloring it reaches the Editor's cached copy. */
    private var cursorDrawable: ColorDrawable? = null

    /**
     * Whether the empty query leads with the recent apps (newest first) and then the
     * alphabetical rest. The home screen's search gesture turns this on; every other
     * caller wants the plain alphabetical picker.
     */
    var sortByUsage = false

    init {
        AppCatalogWatcher.watchAppCatalog {
            catalog = it
            if (active) show()
        }

        // Keep the search bar clear of the status bar and the results clear of the
        // navigation bar and the keyboard, both on the root. Reading the insets the
        // window actually applied is what keeps this right: the bar's own size is not
        // always what the window reserved for it, and guessing leaves a gap above the
        // search bar.
        view.padForSystemBars(ime = true)

        applySearchBarAppearance()
        applyLetterBarAppearance()
    }

    private fun applySearchBarAppearance() {
        val textColor = LauncherConfig.textColor(activity)
        searchInput.setTextColor(textColor)
        val textSp = LauncherConfig.labelTextSp(activity)
        searchInput.textSize = textSp
        searchInput.setHintTextColor(faded(textColor, FADED_ALPHA))
        searchInput.setShadowLayer(3f, 1f, 1f, SHADOW_COLOR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // The Editor caches the cursor drawable the first time it draws it, so a
            // newly set drawable would never be picked up. Keep one instance and only
            // change its color, and the cached reference follows along.
            val cursor =
                cursorDrawable ?: object : ColorDrawable(textColor) {
                    override fun getIntrinsicWidth(): Int = 2f.dpToPx(activity)
                }.also {
                    cursorDrawable = it
                    searchInput.setTextCursorDrawable(it)
                }
            cursor.setColor(textColor)
        }
        val iconSize = (textSp * SEARCH_ICON_DP / LauncherConfig.DEFAULT_LABEL_TEXT_SP).spToPx(activity)
        // The shadow spreads a little past the icon, so give the view that much
        // padding; otherwise the right and bottom of the shadow are clipped.
        val shadowPadding = ShadowedIconDrawable.SHADOW_EXTENT_PX
        searchIcon.setPadding(shadowPadding, shadowPadding, shadowPadding, shadowPadding)
        searchIcon.layoutParams =
            searchIcon.layoutParams.apply {
                width = iconSize + shadowPadding * 2
                height = iconSize + shadowPadding * 2
            }
        // The layout's ic_search is already a shadowed drawable; it only needs the
        // configured text color.
        searchIcon.drawable?.setTint(textColor)
    }

    private fun applyLetterBarAppearance() {
        val textColor = LauncherConfig.textColor(activity)
        val fadedColor = faded(textColor, FADED_ALPHA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            letterBar.verticalScrollbarThumbDrawable
                ?.mutate()
                ?.setTint(fadedColor)
        }
        for ((letter, cell) in letterCells) {
            cell.setTextColor(if (letter == highlighted) textColor else fadedColor)
        }
        recentCellIcon.setTint(if (recentHighlighted) textColor else fadedColor)
    }

    /** [color] with its own alpha scaled by [factor]. */
    private fun faded(
        color: Int,
        factor: Float,
    ): Int {
        val alpha = (Color.alpha(color) * factor).roundToInt()
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
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
        applySearchBarAppearance()
        applyLetterBarAppearance()
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
     * The clock sits above the letters and marks the recent section the empty query
     * shows. It is only visible when that section exists; otherwise the bar starts at
     * the letters, exactly as it did before.
     *
     * The column scrolls, which is what keeps the whole alphabet reachable with the
     * keyboard up: one column of clock plus 26 letters plus '#' is taller than what is
     * left of the screen then, and shrinking the cells to fit would leave them
     * unreadable.
     */
    private fun buildLetterBar() {
        letterBar.removeAllViews()
        letterCells.clear()
        // The cells are rebuilt whenever the size changes, so none of them keeps the
        // highlight from the previous build.
        highlighted = null
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val labelSp = LauncherConfig.labelTextSp(activity)
        val unselected = faded(LauncherConfig.textColor(activity), FADED_ALPHA)

        val metrics = TextView(activity).apply { textSize = labelSp }.paint.fontMetrics
        val textHeight = metrics.descent - metrics.ascent
        val cellSize = (textHeight * 1.5f).roundToInt()
        val iconSize = textHeight.roundToInt()
        val iconInset = ((cellSize - iconSize) / 2f).roundToInt()

        recentCell =
            ImageView(activity).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setPadding(iconInset, iconInset, iconInset, iconInset)
                contentDescription = activity.getString(R.string.recent_apps)
                setImageDrawable(recentCellIcon)
                setOnClickListener { listView?.setSelection(0) }
                visibility = View.GONE
            }
        recentCellIcon.setTint(unselected)
        column.addView(recentCell, LinearLayout.LayoutParams(cellSize, cellSize))

        for (letter in ALL_LETTERS) {
            val cellView = TextView(activity)
            cellView.text = letter.uppercaseChar().toString()
            cellView.textSize = labelSp
            cellView.gravity = Gravity.CENTER
            cellView.setShadowLayer(3f, 1f, 1f, SHADOW_COLOR)
            cellView.setTextColor(unselected)
            cellView.setOnClickListener { jumpTo(letter) }
            cellView.visibility = View.GONE
            letterCells[letter] = cellView

            column.addView(cellView, LinearLayout.LayoutParams(cellSize, cellSize))
        }
        letterBar.addView(column)
    }

    /** Show the letters the current results actually have, and hide the rest. */
    private fun updateLetters() {
        // The recent section is its own entry, the clock; the letters cover only the
        // alphabetical rest below it (and the results while a query is typed).
        recentCell?.visibility = if ((adapter?.recentCount ?: 0) > 0) View.VISIBLE else View.GONE
        val shown = adapter?.letters()?.toSet().orEmpty()
        for ((letter, cellView) in letterCells) {
            cellView.visibility = if (letter in shown) View.VISIBLE else View.GONE
        }
        applyLetterBarAppearance()
    }

    /**
     * Mark the section at the top of the list as the current one: the clock while the
     * recent apps show, otherwise the topmost app's letter. The current entry is bold
     * and in the full text color, while the rest stay muted. Nothing happens while the
     * list is still within the same section.
     */
    private fun highlightVisibleLetter() {
        val adapter = this.adapter ?: return
        val position = listView?.firstVisiblePosition ?: return
        if (position < 0 || position >= adapter.count) return
        val inRecent = position < adapter.recentCount
        val letter = if (inRecent) null else letterOf(adapter.getItem(position) as AppEntry)
        if (letter == highlighted && inRecent == recentHighlighted) return
        highlighted = letter
        recentHighlighted = inRecent
        for ((cellLetter, cell) in letterCells) {
            cell.typeface = if (cellLetter == letter) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        applyLetterBarAppearance()
        // Scroll the current entry into the middle of the scrollable index column.
        val cell = if (inRecent) recentCell else letter?.let { letterCells[it] }
        if (cell != null) {
            val top = cell.top
            val bottom = cell.bottom
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

        /**
         * How many entries at the top of [shown] are the recent section. They are not
         * under any letter, so the index leaves them out and the clock stands for them.
         */
        var recentCount = 0
            private set

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

        /**
         * Distinct index labels in the alphabetical part of the results: '#' first,
         * then a-z.
         */
        fun letters(): List<Char> {
            val groups = shown.subList(recentCount, shown.size).map { letterOf(it) }.distinct()
            return if (NON_LETTER in groups) {
                listOf(NON_LETTER) + groups.filter { it != NON_LETTER }.sorted()
            } else {
                groups.sorted()
            }
        }

        /**
         * First shown position under [letter], below the recent section: for '#' the
         * first label that does not start with an a-z character, otherwise the first
         * under that letter. -1 if there is none.
         */
        fun indexOfLetter(letter: Char): Int {
            for (index in recentCount until shown.size) {
                if (letterOf(shown[index]) == letter) return index
            }
            return -1
        }

        fun filter(query: String) {
            val q = query.trim().lowercase(Locale.ROOT)
            recentCount = 0
            shown =
                when {
                    q.isNotEmpty() -> all.filter { it.searchLabel.contains(q) }
                    sortByUsage -> {
                        // Recent apps first, newest first; everything else keeps the
                        // alphabetical order the catalog already has.
                        val byKey = all.associateBy { it.key }
                        val recent = AppUsage.recentKeys(activity).mapNotNull { byKey[it] }
                        val recentKeys = recent.mapTo(HashSet(recent.size)) { it.key }
                        recentCount = recent.size
                        recent + all.filter { it.key !in recentKeys }
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
                (convertView as? TextView)
                    ?: activity.layoutInflater.inflate(
                        R.layout.item_app_choice,
                        parent,
                        false,
                    ) as TextView

            val entry = shown[position]

            // Tag the row so a late icon load cannot update a recycled row.
            view.tag = entry.key

            view.text = entry.label
            view.textSize = LauncherConfig.labelTextSp(activity)
            view.setTextColor(LauncherConfig.textColor(activity))

            // Configure the compound drawable.
            val iconsShown = LauncherConfig.iconsShown(activity)
            val size = LauncherConfig.iconSizeSp(activity).spToPx(activity)

            if (iconsShown) {
                // Empty icon
                val placeholder =
                    android.graphics.drawable.ColorDrawable(
                        android.graphics.Color.TRANSPARENT,
                    )

                fun setIcon(drawable: Drawable?) {
                    if (view.tag != entry.key) return
                    val icon = ShadowedIconDrawable.wrap(drawable) ?: placeholder
                    icon.setBounds(0, 0, size, size)
                    view.setCompoundDrawablesRelative(null, null, icon, null)
                }

                setIcon(
                    Icons.icon(activity, entry.intent) { loaded ->
                        setIcon(loaded)
                    },
                )
            } else {
                view.setCompoundDrawablesRelative(null, null, null, null)
            }

            view.setOnClickListener {
                AppUsage.record(activity, entry.key)
                activity.searchCompleted(entry.intent)
            }

            view.setOnLongClickListener {
                onAppSettings(entry)
                true
            }

            return view
        }
    }

    companion object {
        const val KEY = "search"

        /** Search icon size in dp at the default text size; it scales with the text size. */
        private const val SEARCH_ICON_DP = 24f

        /** The shadow the launcher's text carries, reused for the search icon. */
        private const val SHADOW_COLOR = 0x99000000.toInt()

        /** How much of the text color's alpha muted text keeps: the hint and the index. */
        private const val FADED_ALPHA = 0.8f

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
