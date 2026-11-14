package io.github.aurelia.nlauncher.ui.screens

import android.app.AlertDialog
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
import io.github.aurelia.nlauncher.ui.DragToReorder
import io.github.aurelia.nlauncher.ui.FavoriteViews
import io.github.aurelia.nlauncher.ui.Screen
import io.github.aurelia.nlauncher.ui.padForSystemBars
import io.github.aurelia.nlauncher.ui.spToPx

class EditFavoritesScreen(
    private val activity: LauncherActivity,
) : Screen {
    override val key = KEY

    override val view: FrameLayout = activity.layoutInflater.inflate(R.layout.screen_edit_favorites, null, false) as FrameLayout

    private val favoritesContainer: LinearLayout = view.findViewById(R.id.edit_favorites)

    private val clearOrDefaults: TextView = view.findViewById(R.id.edit_clear)

    private val favorites = mutableListOf<FavoriteApp>()

    init {
        view.findViewById<View>(R.id.edit_done).setOnClickListener { activity.back() }
        clearOrDefaults.setOnClickListener {
            if (favorites.isEmpty()) restoreDefaults() else confirmClearFavorites()
        }
        // Keep the rows clear of the navigation bar and the title bar below the
        // status bar (the bar's own padding comes from the layout).
        view.padForSystemBars()

        // The "+ Add favorite" row opens the app picker; the pick becomes a favorite.
        activity.onSearchResult(KEY) { intent ->
            favorites.add(FavoriteApp(intent))
            LauncherConfig.favorites.set(activity, favorites)
            populate()
        }
    }

    override fun onActivated() {
        favorites.clear()
        favorites.addAll(LauncherConfig.favorites.get(activity))
        populate()
    }

    private fun populate() {
        favoritesContainer.removeAllViews()
        addAddRow()
        favorites.forEachIndexed { index, favorite -> addEditableRow(index, favorite) }
        // With nothing left to clear, offer the original favorites instead.
        clearOrDefaults.setText(if (favorites.isEmpty()) R.string.defaults else R.string.clear)
    }

    private fun addAddRow() {
        // The "+ Add favorite" row stays pinned at the top, above the favorites.
        val add = activity.layoutInflater.inflate(R.layout.item_add_app, favoritesContainer, false)
        // Its + icon matches the favorites' icon size so the row lines up.
        val addLabel = add.findViewById<TextView>(R.id.add_app_label)
        val addIcon = add.findViewById<ImageView>(R.id.add_app_icon)
        addLabel.textSize = LauncherConfig.labelTextSp.get(activity)
        val addIconSize = LauncherConfig.iconSizeSp.get(activity).spToPx(activity)
        addIcon.layoutParams =
            addIcon.layoutParams.apply {
                width = addIconSize
                height = addIconSize
            }
        add.setOnClickListener { activity.pushSearch(KEY) }
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

        row.findViewById<View>(R.id.favorite_delete).setOnClickListener { removeFavorite(index) }

        // The container holds the "+ Add favorite" row first, so row i of the list is
        // child i + 1.
        DragToReorder(
            row = row,
            size = { favorites.size },
            childAt = { favoritesContainer.getChildAt(it + 1) },
            indexOfRow = { favoritesContainer.indexOfChild(row) - 1 },
            onDrop = { from, to ->
                favorites.add(to, favorites.removeAt(from))
                LauncherConfig.favorites.set(activity, favorites)
            },
            onChanged = ::populate,
        ).attach(row.findViewById(R.id.favorite_handle))

        favoritesContainer.addView(row)
    }

    private fun confirmClearFavorites() {
        AlertDialog
            .Builder(activity)
            .setTitle(R.string.dialog_are_you_sure)
            .setMessage(R.string.clear_favorites_confirmation)
            .setPositiveButton(R.string.clear) { _, _ -> clearFavorites() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearFavorites() {
        favorites.clear()
        LauncherConfig.favorites.set(activity, favorites)
        populate()
    }

    private fun restoreDefaults() {
        favorites.clear()
        favorites.addAll(LauncherConfig.favorites.defaultValue(activity))
        LauncherConfig.favorites.set(activity, favorites)
        populate()
    }

    private fun removeFavorite(index: Int) {
        if (index !in favorites.indices) {
            populate()
            return
        }
        favorites.removeAt(index)
        LauncherConfig.favorites.set(activity, favorites)
        populate()
    }

    companion object {
        const val KEY = "edit_favorites"
    }
}
