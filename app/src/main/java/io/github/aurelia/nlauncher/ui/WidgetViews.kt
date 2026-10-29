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
import java.util.Date

object WidgetViews {
    private fun launchClock(activity: LauncherActivity) {
        // Figure out clock app from ACTION_SHOW_ALARMS intent
        val pm = activity.packageManager
        val alarm = Intent(AlarmClock.ACTION_SHOW_ALARMS)
        val clockPackage = pm.resolveActivity(alarm, 0)?.activityInfo?.packageName
        activity.launch(clockPackage?.let { pm.getLaunchIntentForPackage(it) } ?: alarm)
    }

    private fun alarmText(
        context: Context,
        now: Date,
    ): String {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val next = alarms?.nextAlarmClock?.triggerTime ?: return context.getString(R.string.widget_alarm_none)
        if (next <= now.time) return context.getString(R.string.widget_alarm_none)
        return DateFormat.getTimeFormat(context).format(Date(next))
    }

    private fun createClockView(activity: LauncherActivity) =
        TextView(activity).apply {
            setTextColor(LauncherConfig.textColor(activity))
            setShadowLayer(3f, 1f, 1f, 0x99000000.toInt())
            textSize = LauncherConfig.labelTextSp(activity) * 2.5f
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { launchClock(activity) }
        }

    private fun createDateView(activity: LauncherActivity) =
        TextView(activity).apply {
            setTextColor(LauncherConfig.textColor(activity))
            setShadowLayer(3f, 1f, 1f, 0x99000000.toInt())
            textSize = LauncherConfig.labelTextSp(activity)
            setOnClickListener { launchClock(activity) }
        }

    private fun createAlarmView(activity: LauncherActivity) =
        TextView(activity).apply {
            setTextColor(LauncherConfig.textColor(activity))
            setShadowLayer(3f, 1f, 1f, 0x99000000.toInt())
            textSize = LauncherConfig.labelTextSp(activity)

            setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_alarm,
                0,
                0,
                0,
            )
            compoundDrawablePadding =
                (6f * resources.displayMetrics.density).toInt()
            setOnClickListener {
                activity.launch(Intent(AlarmClock.ACTION_SHOW_ALARMS))
            }
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
    ) = when (widget) {
        Widget.CLOCK -> createClockView(activity)
        Widget.DATE -> createDateView(activity)
        Widget.ALARM -> createAlarmView(activity)
    }.apply { text = text(activity, widget, Date()) }
}
