package io.github.aurelia.nlauncher.ui.screens

import android.app.AlertDialog
import android.view.View
import io.github.aurelia.nlauncher.LauncherActivity
import io.github.aurelia.nlauncher.LauncherConfig
import io.github.aurelia.nlauncher.R
import io.github.aurelia.nlauncher.Widget
import io.github.aurelia.nlauncher.ui.BAR_PADDING_DP
import io.github.aurelia.nlauncher.ui.WidgetViews

class EditWidgetsScreen(
    activity: LauncherActivity,
) : ListEditorScreen<Widget>(
        activity = activity,
        setting = LauncherConfig.widgets,
        titleRes = R.string.edit_widgets,
        addLabelRes = R.string.add_widget,
        addRowAtTop = false,
        barAtTop = false,
        extraTopPaddingDp = BAR_PADDING_DP,
    ) {
    override val key = KEY

    override fun preview(item: Widget): View = WidgetViews.create(activity, item)

    override fun shouldShowAddRow(): Boolean = Widget.entries.any { it !in items }

    override fun onAddClicked() {
        val available = Widget.entries.filterNot { it in items }
        val titles = available.map { activity.getString(WidgetViews.titleRes(it)) }.toTypedArray()
        AlertDialog
            .Builder(activity)
            .setTitle(R.string.add_widget)
            .setItems(titles) { _, which -> append(available[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val KEY = "edit_widgets"
    }
}
