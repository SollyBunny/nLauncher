package io.github.aurelia.nlauncher.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.widget.TextView
import io.github.aurelia.nlauncher.Icons
import io.github.aurelia.nlauncher.LauncherConfig

/** Which side of a label an app icon sits on. */
enum class IconPosition { START, END }

/**
 * Load and draw the app icon for [intent], keeping the slot occupied while it loads.
 * The tag guard makes a late callback harmless after the row has been recycled.
 */
internal fun TextView.bindAppIcon(
    intent: Intent,
    position: IconPosition,
) {
    val size = LauncherConfig.iconSizeSp.get(context).spToPx(context)
    val placeholder = ColorDrawable(Color.TRANSPARENT)
    val boundKey = tag

    fun setIcon(drawable: Drawable?) {
        if (tag != boundKey) return
        val icon = ShadowedIconDrawable.wrap(drawable) ?: placeholder
        icon.setBounds(0, 0, size, size)
        if (position == IconPosition.START) {
            setCompoundDrawablesRelative(icon, null, null, null)
        } else {
            setCompoundDrawablesRelative(null, null, icon, null)
        }
    }

    setIcon(Icons.icon(context, intent) { setIcon(it) })
}

/** Leave the icon slot empty when icons are turned off. */
internal fun TextView.clearAppIcon() = setCompoundDrawablesRelative(null, null, null, null)
