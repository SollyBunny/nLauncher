package io.github.aurelia.nlauncher

import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * The single "pick an app" screen. Both the gesture bindings and the home screen's
 * edit mode go through here, so there is exactly one implementation.
 *
 * It opens as a full-screen search: the search bar sits on top and is auto-focused,
 * the results are a list in the right half of the screen, filtered as you type.
 * Like the home screen, the window is transparent with FLAG_SHOW_WALLPAPER, so the
 * live wallpaper stays visible (and interactive) behind it.
 */
object AppPicker {

    private class AppEntry(val label: String, val icon: Drawable, val intent: Intent)

    fun show(activity: Activity, onPicked: (Intent) -> Unit, onDismiss: () -> Unit = {}) {
        val pm = activity.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
            .asSequence()
            .map { it.activityInfo }
            // Keep the launcher's own home activity out of the picker list.
            .filter { it.packageName != activity.packageName }
            .distinctBy { it.packageName to it.name }
            .map {
                AppEntry(
                    label = it.loadLabel(pm).toString(),
                    icon = it.loadIcon(pm),
                    intent = Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(ComponentName(it.packageName, it.name)),
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()

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
        val adapter = Adapter(activity, apps) { entry ->
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

        // The results take up the right half of the screen, aligned right.
        val halfWidth = (activity.resources.displayMetrics.widthPixels * 0.5f).roundToInt()
        val listView = ListView(activity)
        listView.divider = null
        listView.dividerHeight = 0
        listContainer.addView(
            listView,
            FrameLayout.LayoutParams(
                halfWidth,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP or Gravity.END,
            ),
        )
        listView.adapter = adapter

        // A-Z index down the left. Each letter sits at the same height as the
        // first app starting with that letter, and tapping one jumps the list
        // to it. Letters track the list as it scrolls.
        val letterBar = FrameLayout(activity)
        val letterViews = mutableMapOf<Char, TextView>()
        var cellSize = 0
        listContainer.addView(
            letterBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP or Gravity.START,
            ),
        )

        fun alignLetters() {
            val firstRow = listView.getChildAt(0) ?: return
            val rowHeight = firstRow.height
            if (rowHeight <= 0) return
            val contentTop = firstRow.top - listView.firstVisiblePosition * rowHeight
            for ((letter, cell) in letterViews) {
                val position = adapter.indexOfLetter(letter)
                if (position >= 0) {
                    // Center the letter on its app row rather than top-aligning it.
                    val centered = contentTop + position * rowHeight + (rowHeight - cellSize) / 2f
                    cell.translationY = centered
                }
            }
        }

        fun rebuildLetterBar() {
            letterBar.removeAllViews()
            letterViews.clear()
            val density = activity.resources.displayMetrics.density
            val cell = (22 * density).roundToInt()
            cellSize = cell
            for (letter in adapter.letters()) {
                val cellView = TextView(activity)
                cellView.text = letter.uppercaseChar().toString()
                cellView.textSize = 14f
                cellView.gravity = Gravity.CENTER
                cellView.setTextColor(activity.getColor(R.color.text_primary))
                cellView.setShadowLayer(3f, 1f, 1f, 0x99000000.toInt())
                cellView.setOnClickListener {
                    val position = adapter.indexOfLetter(letter)
                    if (position >= 0) listView.setSelection(position)
                }
                letterBar.addView(
                    cellView,
                    FrameLayout.LayoutParams(cell, cell, Gravity.TOP or Gravity.START),
                )
                letterViews[letter] = cellView
            }
            listView.post { alignLetters() }
        }
        rebuildLetterBar()

        listView.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {}
            override fun onScroll(
                view: AbsListView?,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int,
            ) {
                alignLetters()
            }
        })
        listView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            listView.post { alignLetters() }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
                rebuildLetterBar()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        dialog.setOnDismissListener { onDismiss() }
        searchInput.requestFocus()
        searchInput.setSelection(searchInput.text.length)
        dialog.show()
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
            val alpha = shown.mapNotNull { it.label.firstOrNull()?.lowercaseChar() }
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
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: activity.layoutInflater.inflate(R.layout.item_app_choice, parent, false)
            val entry = shown[position]
            val icon = view.findViewById<ImageView>(R.id.app_choice_icon)
            val label = view.findViewById<TextView>(R.id.app_choice_label)
            icon.setImageDrawable(entry.icon)
            label.text = entry.label

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
