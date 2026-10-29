package io.github.aurelia.nlauncher.ui

import android.content.Context
import android.util.TypedValue
import kotlin.math.roundToInt

const val CLOCK_TICK_MS = 60_000L
const val SWIPE_MIN_DP = 48f
const val BAR_PADDING_DP = 12f

/**
 * Widget text sizes as multiples of the app label size: the clock was 48sp and the
 * date and alarm 18sp when labels were 18sp, so they keep their proportions as Look
 * and feel scales the labels.
 */
const val CLOCK_TEXT_SCALE = 48f / 18f
const val DATE_TEXT_SCALE = 1f
const val COMPOUND_ICON_PADDING_DP = 6
const val WIDGET_GAP_DP = 4f

/** [this] sp in pixels, for sizes that are not text, such as icons. */
fun Float.spToPx(context: Context): Int =
    TypedValue
        .applyDimension(TypedValue.COMPLEX_UNIT_SP, this, context.resources.displayMetrics)
        .roundToInt()
