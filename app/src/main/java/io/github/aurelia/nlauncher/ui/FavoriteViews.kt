package io.github.aurelia.nlauncher.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.aurelia.nlauncher.FavoriteApp
import io.github.aurelia.nlauncher.Favorites
import io.github.aurelia.nlauncher.LauncherConfig
import io.github.aurelia.nlauncher.R

object FavoriteViews {
    fun create(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        favorite: FavoriteApp,
        context: Context,
    ): View {
        val view = inflater.inflate(R.layout.item_favorite, parent, false) as TextView

        // Remember which app the row is for, so a late icon cannot update a
        // row that has since been rebuilt for another app.
        view.tag = favorite.key
        view.text = Favorites.label(context, favorite)
        view.textSize = LauncherConfig.labelTextSp.get(context)
        view.setTextColor(LauncherConfig.resolvedTextColor(context))

        if (LauncherConfig.iconsShown.get(context)) {
            view.bindAppIcon(favorite.intent, IconPosition.START)
        } else {
            view.clearAppIcon()
        }

        return view
    }
}
