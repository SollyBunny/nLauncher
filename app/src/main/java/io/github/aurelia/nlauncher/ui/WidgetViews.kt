package io.github.aurelia.nlauncher.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.provider.AlarmClock
import android.text.format.DateFormat
import android.widget.TextView
import io.github.aurelia.nlauncher.LauncherActivity
import io.github.aurelia.nlauncher.LauncherConfig
import io.github.aurelia.nlauncher.R
import io.github.aurelia.nlauncher.Widget
import io.github.aurelia.nlauncher.appifyIntent
import java.util.Date

object WidgetViews {
    private fun alarmText(
        context: Context,
        now: Date,
    ): String {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val next = alarms?.nextAlarmClock?.triggerTime ?: return context.getString(R.string.widget_alarm_none)
        if (next <= now.time) return context.getString(R.string.widget_alarm_none)
        return DateFormat.getTimeFormat(context).format(Date(next))
    }

    /** The clock and date open the Clock app; the alarm action is only how we find it. */
    private fun launchClockApp(activity: LauncherActivity) {
        activity.launch(appifyIntent(activity, Intent(AlarmClock.ACTION_SHOW_ALARMS)))
    }

    /** The alarm widget goes to the alarm screen, not the Clock app's home activity. */
    private fun launchAlarmScreen(activity: LauncherActivity) {
        activity.launch(Intent(AlarmClock.ACTION_SHOW_ALARMS))
    }

    private fun createView(
        activity: LauncherActivity,
        scale: Float,
        bold: Boolean = false,
        alarmIcon: Boolean = false,
        onClick: (() -> Unit)? = null,
    ): TextView =
        TextView(activity).apply {
            setTextColor(LauncherConfig.resolvedTextColor(activity))
            setLauncherShadow()
            textSize = LauncherConfig.labelTextSp.get(activity) * scale
            if (bold) typeface = Typeface.DEFAULT_BOLD
            if (alarmIcon) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_alarm, 0, 0, 0)
                compoundDrawablePadding = (6f * resources.displayMetrics.density).toInt()
            }
            if (onClick != null) setOnClickListener { onClick() }
        }

    fun titleRes(widget: Widget): Int =
        when (widget) {
            Widget.CLOCK -> R.string.widget_clock
            Widget.DATE -> R.string.widget_date
            Widget.ALARM -> R.string.widget_alarm
        }

    fun text(
        context: Context,
        widget: Widget,
        now: Date,
    ): String =
        when (widget) {
            Widget.CLOCK -> DateFormat.getTimeFormat(context).format(now)
            Widget.DATE -> DateFormat.getDateFormat(context).format(now)
            Widget.ALARM -> alarmText(context, now)
        }

    fun create(
        activity: LauncherActivity,
        widget: Widget,
    ): TextView =
        when (widget) {
            Widget.CLOCK -> createView(activity, 2.5f, bold = true) { launchClockApp(activity) }
            Widget.DATE -> createView(activity, 1f) { launchClockApp(activity) }
            Widget.ALARM -> createView(activity, 1f, alarmIcon = true) { launchAlarmScreen(activity) }
        }.apply { text = text(activity, widget, Date()) }
}
