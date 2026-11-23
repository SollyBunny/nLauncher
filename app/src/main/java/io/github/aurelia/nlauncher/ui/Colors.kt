package io.github.aurelia.nlauncher.ui

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.widget.TextView
import java.util.Locale

/** The single drop shadow used by text and icons throughout the launcher. */
const val TEXT_SHADOW_COLOR = 0x99000000.toInt()

/** Apply the launcher's text shadow, identically everywhere. */
fun TextView.setLauncherShadow() = setShadowLayer(3f, 1f, 1f, TEXT_SHADOW_COLOR)

/** The canonical opaque #RRGGBB form shown in settings and the colour picker. */
fun colorToHex(color: Int): String = String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)

/** Black on a light color, white on a dark one. */
fun contrastingTextColor(color: Int): Int {
    val luminance =
        0.299f * Color.red(color) / 255f +
            0.587f * Color.green(color) / 255f +
            0.114f * Color.blue(color) / 255f
    return if (luminance > 0.6f) Color.BLACK else Color.WHITE
}

/**
 * Adaptive text: white over a dark wallpaper, black over a light one, decided by the
 * wallpaper's primary color. WallpaperColors arrived in O_MR1; older versions, and
 * wallpapers that report no colors, keep the launcher's original white text.
 */
fun adaptiveTextColor(context: Context): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        val primary =
            WallpaperManager
                .getInstance(context)
                .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                ?.primaryColor
        if (primary != null) return contrastingTextColor(primary.toArgb())
    }
    return Color.WHITE
}
