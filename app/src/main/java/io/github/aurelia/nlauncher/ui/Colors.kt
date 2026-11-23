package io.github.aurelia.nlauncher.ui

import android.widget.TextView
import java.util.Locale

/** The single drop shadow used by text and icons throughout the launcher. */
const val TEXT_SHADOW_COLOR = 0x99000000.toInt()

/** Apply the launcher's text shadow, identically everywhere. */
fun TextView.setLauncherShadow() = setShadowLayer(3f, 1f, 1f, TEXT_SHADOW_COLOR)

/** The canonical opaque #RRGGBB form shown in settings and the colour picker. */
fun colorToHex(color: Int): String = String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)
