package io.github.aurelia.nlauncher.ui

import android.content.Context
import android.util.TypedValue
import kotlin.math.roundToInt

const val CLOCK_TICK_MS = 60_000L
const val SWIPE_MIN_DP = 48f
const val BAR_PADDING_DP = 12f
const val WIDGET_GAP_DP = 4f

fun Float.spToPx(context: Context): Int =
    TypedValue
        .applyDimension(TypedValue.COMPLEX_UNIT_SP, this, context.resources.displayMetrics)
        .roundToInt()

fun Float.dpToPx(context: Context): Int =
    TypedValue
        .applyDimension(TypedValue.COMPLEX_UNIT_DIP, this, context.resources.displayMetrics)
        .roundToInt()
