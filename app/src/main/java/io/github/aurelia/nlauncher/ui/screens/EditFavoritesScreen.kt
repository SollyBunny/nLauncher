package io.github.aurelia.nlauncher.ui.screens

import android.content.Intent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.aurelia.nlauncher.FavoriteApp
import io.github.aurelia.nlauncher.LauncherActivity
import io.github.aurelia.nlauncher.LauncherConfig
import io.github.aurelia.nlauncher.R
import io.github.aurelia.nlauncher.ui.APP_LABEL_TEXT_SIZE
import io.github.aurelia.nlauncher.ui.FavoriteViews
import io.github.aurelia.nlauncher.ui.ICON_TEXT_SCALE
import io.github.aurelia.nlauncher.ui.Screen
import io.github.aurelia.nlauncher.ui.padForSystemBars
import kotlin.math.roundToInt

/**
 * The favorites editor: its own screen, separate from [HomeScreen] rather than a mode
 * of it. Each favorite gets a drag handle and a delete button around the exact row the
 * home screen shows, the "+ Add favorite" row stays pinned at the top, and "Done"
 * pops back to whatever screen the user came from.
 */
class EditFavoritesScreen(
    private val activity: LauncherActivity,
) : Screen {
    override val key = KEY

    override val view: FrameLayout = activity.layoutInflater.inflate(R.layout.screen_edit_favorites, null, false) as FrameLayout

    private val favoritesContainer: LinearLayout = view.findViewById(R.id.edit_favorites)

    private val favorites = mutableListOf<FavoriteApp>()

    init {
        view.findViewById<View>(R.id.edit_done).setOnClickListener { activity.back() }
        // Keep the rows clear of the navigation bar and the title bar below the
        // status bar (the bar's own padding comes from the layout).
        view.padForSystemBars()
    }

    override fun onActivated() {
        favorites.clear()
        favorites.addAll(LauncherConfig.favorites(activity))
        populate()
    }

    private fun populate() {
        favoritesContainer.removeAllViews()
        addAddRow()
        favorites.forEachIndexed { index, favorite -> addEditableRow(index, favorite) }
    }

    private fun addAddRow() {
        // The "+ Add favorite" row stays pinned at the top, above the favorites.
        val add = activity.layoutInflater.inflate(R.layout.item_add_app, favoritesContainer, false)
        // Its + icon matches the favorites' icon size so the row lines up.
        val addLabel = add.findViewById<TextView>(R.id.add_app_label)
        val addIcon = add.findViewById<ImageView>(R.id.add_app_icon)
        addLabel.textSize = APP_LABEL_TEXT_SIZE
        val addIconSize = (addLabel.textSize * ICON_TEXT_SCALE).roundToInt()
        addIcon.layoutParams =
            addIcon.layoutParams.apply {
                width = addIconSize
                height = addIconSize
            }
        add.setOnClickListener {
            activity.openSearch { intent -> addFavorite(intent) }
        }
        favoritesContainer.addView(add)
    }

    private fun addEditableRow(
        index: Int,
        favorite: FavoriteApp,
    ) {
        val row = activity.layoutInflater.inflate(R.layout.item_favorite_edit, favoritesContainer, false)

        // The very same view the home screen shows, just inert while editing.
        val preview = FavoriteViews.create(activity.layoutInflater, null, favorite, activity)
        preview.isClickable = false
        preview.isFocusable = false
        row.findViewById<FrameLayout>(R.id.favorite_preview).addView(
            preview,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        row.findViewById<View>(R.id.favorite_handle).setOnTouchListener(DragTouchListener(row))
        row.findViewById<View>(R.id.favorite_delete).setOnClickListener { removeFavorite(index) }
        favoritesContainer.addView(row)
    }

    /** Add a picked app to the end of the favorites and save the new order. */
    private fun addFavorite(intent: Intent) {
        favorites.add(FavoriteApp(intent))
        LauncherConfig.saveFavorites(activity, favorites)
        populate()
    }

    private fun removeFavorite(index: Int) {
        if (index !in favorites.indices) {
            populate()
            return
        }
        favorites.removeAt(index)
        LauncherConfig.saveFavorites(activity, favorites)
        populate()
    }

    /** Drag a row by its handle to reorder it. */
    private inner class DragTouchListener(
        private val row: View,
    ) : View.OnTouchListener {
        private var startRawY = 0f
        private var dragging = false

        override fun onTouch(
            v: View,
            event: MotionEvent,
        ): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    startRawY = event.rawY
                    row.elevation = dp(8f).toFloat()
                    row.parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) row.translationY = event.rawY - startRawY
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        row.parent?.requestDisallowInterceptTouchEvent(false)
                        dropFavorite(row)
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun dropFavorite(row: View) {
        // The first child is the "+ Add favorite" row; favorite rows follow it.
        val from = favoritesContainer.indexOfChild(row) - 1
        if (from < 0 || from >= favorites.size) {
            populate()
            return
        }

        val center = row.top + row.translationY + row.height / 2f
        var to = 0
        for (i in favorites.indices) {
            if (i == from) continue
            // Rows are laid out as [add] + favorites; skip anything unexpected
            // rather than letting a missing child crash the drag.
            val other = favoritesContainer.getChildAt(i + 1) ?: continue
            if (center > other.top + other.height / 2f) to++
        }

        if (to != from) {
            favorites.add(to, favorites.removeAt(from))
            LauncherConfig.saveFavorites(activity, favorites)
        }
        populate()
    }

    private fun dp(value: Float): Int = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        const val KEY = "edit_favorites"
    }
}
