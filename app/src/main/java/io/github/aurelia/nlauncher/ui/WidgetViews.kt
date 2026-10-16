package io.github.aurelia.nlauncher.ui

import android.content.Context
import android.graphics.Typeface
import android.text.format.DateFormat
import android.widget.TextView
import io.github.aurelia.nlauncher.R
import io.github.aurelia.nlauncher.Widget
import java.util.Date

/** Text size (sp) of the clock widget. */
const val CLOCK_TEXT_SIZE = 48f

/** Text size (sp) of the date widget. */
const val DATE_TEXT_SIZE = 18f

/**
 * Builds a widget view. The home screen and the editor's preview both go through
 * here, so the widget being edited is guaranteed to look exactly like the real one.
 */
object WidgetViews {
    /** The widget's name, for the editor's add picker. */
    fun titleRes(widget: Widget): Int =
        when (widget) {
            Widget.CLOCK -> R.string.widget_clock
            Widget.DATE -> R.string.widget_date
        }

    /** [widget]'s current text, honoring the user's locale settings. */
    fun text(
        context: Context,
        widget: Widget,
        now: Date = Date(),
    ): String =
        when (widget) {
            Widget.CLOCK -> DateFormat.getTimeFormat(context).format(now)
            Widget.DATE -> DateFormat.getDateFormat(context).format(now)
        }

    /** The exact view the home screen shows for [widget]. */
    fun create(
        context: Context,
        widget: Widget,
    ): TextView =
        TextView(context).apply {
            textSize =
                when (widget) {
                    Widget.CLOCK -> CLOCK_TEXT_SIZE
                    Widget.DATE -> DATE_TEXT_SIZE
                }
            typeface = if (widget == Widget.CLOCK) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(context.getColor(R.color.text_primary))
            // Same shadow the XML layouts use: keeps the text readable over any wallpaper.
            setShadowLayer(3f, 1f, 1f, 0x99000000.toInt())
            text = WidgetViews.text(context, widget)
        }
}
