package io.github.aurelia.nlauncher.ui.screens

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.aurelia.nlauncher.LauncherActivity
import io.github.aurelia.nlauncher.LauncherConfig
import io.github.aurelia.nlauncher.R
import io.github.aurelia.nlauncher.Widget
import io.github.aurelia.nlauncher.ui.APP_LABEL_TEXT_SIZE
import io.github.aurelia.nlauncher.ui.BAR_PADDING_DP
import io.github.aurelia.nlauncher.ui.DragToReorder
import io.github.aurelia.nlauncher.ui.ICON_TEXT_SCALE
import io.github.aurelia.nlauncher.ui.Screen
import io.github.aurelia.nlauncher.ui.WidgetViews
import io.github.aurelia.nlauncher.ui.padForSystemBars
import kotlin.math.roundToInt

/**
 * The widgets editor, the sibling of [EditFavoritesScreen]. Each widget gets a drag
 * handle and a delete button around the exact view the home screen shows, the
 * "+ Add widget" row sits above them, and the title with "Done" runs along the
 * *bottom* — the widgets live at the top of the screen, so a top bar would cover
 * them. "Done" pops back to whatever screen the user came from.
 */
class EditWidgetsScreen(
    private val activity: LauncherActivity,
) : Screen {
    override val key = KEY

    override val view: FrameLayout = activity.layoutInflater.inflate(R.layout.screen_edit_widgets, null, false) as FrameLayout

    private val widgetsContainer: LinearLayout = view.findViewById(R.id.edit_widgets)

    private val widgets = mutableListOf<Widget>()

    init {
        view.findViewById<View>(R.id.edit_done).setOnClickListener { activity.back() }
        // The rows sit at the top of the screen: keep them below the status bar with
        // the same breathing room the home screen gives its widgets.
        view.padForSystemBars(topExtraDp = BAR_PADDING_DP)
    }

    override fun onActivated() {
        widgets.clear()
        widgets.addAll(LauncherConfig.widgets(activity))
        populate()
    }

    private fun populate() {
        widgetsContainer.removeAllViews()
        widgets.forEachIndexed { index, widget -> addEditableRow(index, widget) }
        addAddRow()
    }

    /**
     * The "+ Add widget" row, below the widgets. There is nothing to add once every
     * widget is already there, so the row stays away then.
     */
    private fun addAddRow() {
        val available = Widget.entries.filterNot { it in widgets }
        if (available.isEmpty()) return

        val add = activity.layoutInflater.inflate(R.layout.item_add_widget, widgetsContainer, false)
        // Its + icon matches the widgets' handle size so the row lines up.
        val addLabel = add.findViewById<TextView>(R.id.add_widget_label)
        val addIcon = add.findViewById<ImageView>(R.id.add_widget_icon)
        addLabel.textSize = APP_LABEL_TEXT_SIZE
        val addIconSize = (addLabel.textSize * ICON_TEXT_SCALE).roundToInt()
        addIcon.layoutParams =
            addIcon.layoutParams.apply {
                width = addIconSize
                height = addIconSize
            }
        add.setOnClickListener { chooseWidget(available) }
        widgetsContainer.addView(add)
    }

    /** Pick one of the widgets that is not on the home screen yet. */
    private fun chooseWidget(available: List<Widget>) {
        val titles = available.map { activity.getString(WidgetViews.titleRes(it)) }.toTypedArray()
        AlertDialog
            .Builder(activity)
            .setTitle(R.string.add_widget)
            .setItems(titles) { _, which -> addWidget(available[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addEditableRow(
        index: Int,
        widget: Widget,
    ) {
        val row = activity.layoutInflater.inflate(R.layout.item_widget_edit, widgetsContainer, false)

        // The very same view the home screen shows, just inert while editing.
        val preview = WidgetViews.create(activity, widget)
        preview.isClickable = false
        preview.isFocusable = false
        row.findViewById<FrameLayout>(R.id.widget_preview).addView(
            preview,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        row.findViewById<View>(R.id.widget_delete).setOnClickListener { removeWidget(index) }

        // The widget rows come first here and the "+ Add widget" row after them, so a
        // widget row's place in the container is its place in the list.
        DragToReorder(
            row = row,
            size = { widgets.size },
            childAt = { widgetsContainer.getChildAt(it) },
            indexOfRow = { widgetsContainer.indexOfChild(row) },
            onDrop = { from, to ->
                widgets.add(to, widgets.removeAt(from))
                LauncherConfig.saveWidgets(activity, widgets)
            },
            onChanged = ::populate,
        ).attach(row.findViewById(R.id.widget_handle))

        widgetsContainer.addView(row)
    }

    /** Add a widget to the end of the list and save the new order. */
    private fun addWidget(widget: Widget) {
        widgets.add(widget)
        LauncherConfig.saveWidgets(activity, widgets)
        populate()
    }

    private fun removeWidget(index: Int) {
        if (index !in widgets.indices) {
            populate()
            return
        }
        widgets.removeAt(index)
        LauncherConfig.saveWidgets(activity, widgets)
        populate()
    }

    companion object {
        const val KEY = "edit_widgets"
    }
}
