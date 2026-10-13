package io.github.aurelia.nlauncher

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.roundToInt

/** App icons are drawn at this multiple of the label's text size. */
const val ICON_TEXT_SCALE = 1.8f

/**
 * Builds a favorite row. The home screen and the settings preview both go through
 * here, so the preview is guaranteed to look exactly like the real thing.
 */
object FavoriteViews {

    fun create(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        favorite: FavoriteApp,
        context: Context,
    ): View {
        val resolved = favorite.resolve(context)
        val view = inflater.inflate(R.layout.item_favorite, parent, false)
        val icon = view.findViewById<ImageView>(R.id.favorite_icon)
        val label = view.findViewById<TextView>(R.id.favorite_label)
        icon.setImageDrawable(resolved.icon)
        label.text = resolved.label

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
