package io.github.aurelia.nlauncher.ui

import android.app.AlarmManager
import android.content.Context
import android.graphics.Typeface
import android.text.format.DateFormat
import android.widget.TextView
import io.github.aurelia.nlauncher.LauncherConfig
import io.github.aurelia.nlauncher.R
import io.github.aurelia.nlauncher.Widget
import java.util.Date

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
            Widget.ALARM -> R.string.widget_alarm
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
            Widget.ALARM -> alarmText(context, now)
        }

    /**
     * The time the next alarm is set for, or a short note that there is none. The
     * widget's icon is what marks it as the alarm, so the text is the time alone.
     *
     * The system keeps the time of the next alarm for exactly this purpose, and asks
     * for it to be shown on the lock screen, so reading it is the same value the
     * user's own clock app is showing. It can be null or in the past: no alarm app has
     * set one, or the only alarm left today has already gone off.
     */
    private fun alarmText(
        context: Context,
        now: Date,
    ): String {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val next = alarms?.nextAlarmClock?.triggerTime ?: return context.getString(R.string.widget_alarm_none)
        if (next <= now.time) return context.getString(R.string.widget_alarm_none)
        return DateFormat.getTimeFormat(context).format(Date(next))
    }

    fun create(
        context: Context,
        widget: Widget,
    ): TextView =
        TextView(context).apply {
            // Widgets are multiples of the app label size, so one setting scales the
            // whole screen while the clock stays much bigger than the date.
            textSize =
                LauncherConfig.labelTextSp(context) *
                when (widget) {
                    Widget.CLOCK -> CLOCK_TEXT_SCALE
                    Widget.DATE, Widget.ALARM -> DATE_TEXT_SCALE
                }

            typeface =
                if (widget == Widget.CLOCK) {
                    Typeface.DEFAULT_BOLD
                } else {
                    Typeface.DEFAULT
                }

            setTextColor(LauncherConfig.textColor(context))
            setShadowLayer(3f, 1f, 1f, 0x99000000.toInt())

            if (widget == Widget.ALARM) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_alarm,
                    0,
                    0,
                    0,
                )
                compoundDrawablePadding =
                    (COMPOUND_ICON_PADDING_DP * resources.displayMetrics.density).toInt()
            }

            text = WidgetViews.text(context, widget)
        }
}
