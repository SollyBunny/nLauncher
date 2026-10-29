package io.github.aurelia.nlauncher.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.aurelia.nlauncher.FavoriteApp
import io.github.aurelia.nlauncher.Favorites
import io.github.aurelia.nlauncher.Icons
import io.github.aurelia.nlauncher.LauncherConfig
import io.github.aurelia.nlauncher.R

/**
 * Builds a favorite row. The home screen and the editor's preview both go through
 * here, so the row being edited is guaranteed to look exactly like the real one.
 */
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
        view.textSize = LauncherConfig.labelTextSp(context)
        view.setTextColor(LauncherConfig.textColor(context))

        val iconsShown = LauncherConfig.iconsShown(context)
        val size = LauncherConfig.iconSizeSp(context).spToPx(context)

        // Keep the icon slot occupied even while the icon is unavailable.
        val placeholder = ColorDrawable(Color.TRANSPARENT)

        fun setIcon(drawable: Drawable?) {
            if (view.tag != favorite.key) return

            view.setCompoundDrawablesRelative(
                (drawable ?: placeholder).apply {
                    setBounds(0, 0, size, size)
                },
                null,
                null,
                null,
            )
        }

        if (iconsShown) {
            setIcon(
                Icons.icon(context, favorite.intent) { loaded ->
                    setIcon(loaded)
                },
            )
        } else {
            view.setCompoundDrawablesRelative(null, null, null, null)
        }

        return view
    }
}
