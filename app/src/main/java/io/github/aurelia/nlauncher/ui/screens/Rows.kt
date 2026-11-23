package io.github.aurelia.nlauncher.ui.screens

import android.app.Activity
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.aurelia.nlauncher.LauncherActivity
import io.github.aurelia.nlauncher.R
import io.github.aurelia.nlauncher.ui.padForSystemBars

/** A panel of rows that scrolls once it outgrows the screen. */
internal fun scrollableRows(
    activity: Activity,
    inner: View,
): View =
    ScrollView(activity).apply {
        isFillViewport = true
        addView(
            inner,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

/** Fill the shared settings header: the launcher icon or a back arrow, plus a title. */
internal fun setHeader(
    activity: LauncherActivity,
    view: View,
    titleRes: Int,
    showBack: Boolean,
) {
    val icon = view.findViewById<ImageView>(R.id.settings_icon)
    icon.setImageResource(if (showBack) R.drawable.ic_arrow_back else R.drawable.ic_launcher)
    // The arrow is tinted like the title; the launcher icon keeps its own colours.
    icon.imageTintList =
        if (showBack) ColorStateList.valueOf(activity.getColor(R.color.text_primary)) else null
    icon.contentDescription = if (showBack) activity.getString(R.string.back) else null
    if (showBack) {
        icon.setOnClickListener { activity.back() }
    } else {
        icon.setOnClickListener(null)
        icon.isClickable = false
        icon.setBackgroundResource(0)
    }
    view.findViewById<TextView>(R.id.settings_title).setText(titleRes)
    view.padForSystemBars()
}

/** A row naming a setting, with its current value after it. */
internal fun settingRow(
    activity: LauncherActivity,
    parent: ViewGroup,
    titleRes: Int,
    summary: CharSequence,
    valueColor: Int? = null,
    onClick: () -> Unit,
): View {
    val row = activity.layoutInflater.inflate(R.layout.item_row, parent, false)
    row.findViewById<TextView>(R.id.row_title).setText(titleRes)
    row.findViewById<TextView>(R.id.row_summary).apply {
        text = summary
        if (valueColor != null) setTextColor(valueColor)
    }
    row.setOnClickListener { onClick() }
    return row
}

/** A menu entry: a title and a chevron, with no summary. */
internal fun menuRow(
    activity: LauncherActivity,
    parent: ViewGroup,
    titleRes: Int,
    onClick: () -> Unit,
): View {
    val row = settingRow(activity, parent, titleRes, "", onClick = onClick)
    row.findViewById<View>(R.id.row_summary).visibility = View.GONE
    return row
}

/** The vertical list every settings screen fills. */
internal fun settingsList(activity: Activity): LinearLayout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
