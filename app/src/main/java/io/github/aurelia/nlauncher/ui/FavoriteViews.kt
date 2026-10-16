package io.github.aurelia.nlauncher.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import io.github.aurelia.nlauncher.FavoriteApp
import io.github.aurelia.nlauncher.Favorites
import io.github.aurelia.nlauncher.R
import kotlin.math.roundToInt

/** Text size (sp) of an app's label, everywhere it is shown. */
const val APP_LABEL_TEXT_SIZE = 18f

/** App icons are drawn at this multiple of the label's text size. */
const val ICON_TEXT_SCALE = 2.2f

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
        val view = inflater.inflate(R.layout.item_favorite, parent, false)
        val icon = view.findViewById<ImageView>(R.id.favorite_icon)
        val label = view.findViewById<TextView>(R.id.favorite_label)

        // Remember which app the row is for, so an icon that arrives late does not
        // land on a row that has meanwhile been rebuilt for another one.
        icon.tag = favorite.key
        label.text = Favorites.label(context, favorite)
        label.textSize = APP_LABEL_TEXT_SIZE

        // Icons sit at ICON_TEXT_SCALE times the label's text size.
        val size = (label.textSize * ICON_TEXT_SCALE).roundToInt()
        val params = icon.layoutParams
        if (params.width != size || params.height != size) {
            params.width = size
            params.height = size
            icon.layoutParams = params
        }

        // The icon may still be loading in the background: draw what there is now and
        // let the callback fill the row in when it lands.
        icon.setImageDrawable(
            Favorites.icon(context, favorite) { loaded ->
                if (icon.tag == favorite.key) icon.setImageDrawable(loaded)
            },
        )
        return view
    }
}
