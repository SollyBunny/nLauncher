package io.github.aurelia.nlauncher.ui.screens

import android.app.AlertDialog
import android.view.View
import io.github.aurelia.nlauncher.FavoriteApp
import io.github.aurelia.nlauncher.LauncherActivity
import io.github.aurelia.nlauncher.LauncherConfig
import io.github.aurelia.nlauncher.R
import io.github.aurelia.nlauncher.ui.FavoriteViews

class EditFavoritesScreen(
    activity: LauncherActivity,
) : ListEditorScreen<FavoriteApp>(
        activity = activity,
        setting = LauncherConfig.favorites,
        titleRes = R.string.edit_favorites,
        addLabelRes = R.string.add_favorite,
        addRowAtTop = true,
        barAtTop = true,
        showClearButton = true,
    ) {
    override val key = KEY

    init {
        // The "+ Add favorite" row opens the app picker; the pick becomes a favorite.
        activity.onSearchResult(KEY) { intent -> append(FavoriteApp(intent)) }
    }

    override fun preview(item: FavoriteApp): View = FavoriteViews.create(activity.layoutInflater, null, item, activity)

    override fun onAddClicked() = activity.pushSearch(KEY)

    override fun onPopulated() {
        clearButton.setText(if (items.isEmpty()) R.string.defaults else R.string.clear)
    }

    override fun onClearClicked() {
        if (items.isEmpty()) {
            items.clear()
            items.addAll(LauncherConfig.favorites.defaultValue(activity))
            LauncherConfig.favorites.set(activity, items)
            populate()
        } else {
            AlertDialog
                .Builder(activity)
                .setTitle(R.string.dialog_are_you_sure)
                .setMessage(R.string.clear_favorites_confirmation)
                .setPositiveButton(R.string.clear) { _, _ ->
                    items.clear()
                    LauncherConfig.favorites.set(activity, items)
                    populate()
                }.setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    companion object {
        const val KEY = "edit_favorites"
    }
}
