package io.github.aurelia.nlauncher.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.aurelia.nlauncher.LauncherActivity
import io.github.aurelia.nlauncher.LauncherConfig
import io.github.aurelia.nlauncher.R
import io.github.aurelia.nlauncher.SettingList
import io.github.aurelia.nlauncher.ui.DragToReorder
import io.github.aurelia.nlauncher.ui.Screen
import io.github.aurelia.nlauncher.ui.dpToPx
import io.github.aurelia.nlauncher.ui.padForSystemBars
import io.github.aurelia.nlauncher.ui.spToPx
import io.github.aurelia.nlauncher.ui.wrapContentParams

/**
 * The list an editor maintains: reload it on activation, redraw it on every change,
 * and save the whole list back through its setting. The two concrete editors only
 * supply what makes them different — the preview view, where the add row sits, and
 * how a new item is picked.
 */
abstract class ListEditorScreen<T>(
    protected val activity: LauncherActivity,
    private val setting: SettingList<T>,
    titleRes: Int,
    private val addLabelRes: Int,
    private val addRowAtTop: Boolean,
    barAtTop: Boolean,
    extraTopPaddingDp: Float = 0f,
    private val showClearButton: Boolean = false,
) : Screen {
    final override val view: FrameLayout =
        activity.layoutInflater.inflate(R.layout.screen_edit, null, false) as FrameLayout

    private val container: LinearLayout = view.findViewById(R.id.edit_list)
    protected val clearButton: TextView = view.findViewById(R.id.edit_clear)

    /** The add row is child 0 for favorites, but the last child for widgets. */
    private val rowOffset get() = if (addRowAtTop) 1 else 0

    protected val items = mutableListOf<T>()

    init {
        view.findViewById<TextView>(R.id.edit_title).setText(titleRes)
        view.findViewById<View>(R.id.edit_done).setOnClickListener { activity.back() }

        val listParams = container.layoutParams as FrameLayout.LayoutParams
        listParams.gravity = (if (barAtTop) Gravity.BOTTOM else Gravity.TOP) or Gravity.START
        container.setPaddingBottom(if (barAtTop) 8f.dpToPx(activity) else 0)

        val bar = view.findViewById<View>(R.id.edit_bar)
        val barParams = bar.layoutParams as FrameLayout.LayoutParams
        barParams.gravity = if (barAtTop) Gravity.TOP else Gravity.BOTTOM
        bar.setPadding(
            16f.dpToPx(activity),
            (if (barAtTop) 12f else 8f).dpToPx(activity),
            8f.dpToPx(activity),
            (if (barAtTop) 8f else 12f).dpToPx(activity),
        )

        clearButton.visibility = if (showClearButton) View.VISIBLE else View.GONE
        if (showClearButton) clearButton.setOnClickListener { onClearClicked() }

        view.padForSystemBars(topExtraDp = extraTopPaddingDp)
    }

    override fun onActivated() {
        items.clear()
        items.addAll(setting.get(activity))
        populate()
    }

    protected fun append(item: T) {
        items.add(item)
        save()
        populate()
    }

    protected open fun shouldShowAddRow(): Boolean = true

    protected abstract fun preview(item: T): View

    protected abstract fun onAddClicked()

    protected open fun onPopulated() {}

    protected open fun onClearClicked() {}

    private fun save() = setting.set(activity, items)

    protected fun populate() {
        container.removeAllViews()
        if (addRowAtTop) addAddRow()
        items.forEachIndexed { index, item -> addEditableRow(index, item) }
        if (!addRowAtTop) addAddRow()
        onPopulated()
    }

    private fun addAddRow() {
        if (!shouldShowAddRow()) return
        val add = activity.layoutInflater.inflate(R.layout.item_add_row, container, false)
        add.findViewById<TextView>(R.id.add_label).apply {
            setText(addLabelRes)
            textSize = LauncherConfig.labelTextSp.get(activity)
        }
        val icon = add.findViewById<ImageView>(R.id.add_icon)
        val size = LauncherConfig.iconSizeSp.get(activity).spToPx(activity)
        icon.layoutParams =
            icon.layoutParams.apply {
                width = size
                height = size
            }
        add.setOnClickListener { onAddClicked() }
        container.addView(add)
    }

    private fun addEditableRow(
        index: Int,
        item: T,
    ) {
        val row = activity.layoutInflater.inflate(R.layout.item_edit_row, container, false)

        // The very same view the home screen shows, just inert while editing.
        val preview = preview(item)
        preview.isClickable = false
        preview.isFocusable = false
        row.findViewById<FrameLayout>(R.id.edit_preview).addView(preview, wrapContentParams())

        row.findViewById<View>(R.id.edit_delete).setOnClickListener { remove(index) }

        DragToReorder(
            row = row,
            size = { items.size },
            childAt = { container.getChildAt(it + rowOffset) },
            indexOfRow = { container.indexOfChild(row) - rowOffset },
            onDrop = { from, to ->
                items.add(to, items.removeAt(from))
                save()
            },
            onChanged = ::populate,
        ).attach(row.findViewById(R.id.edit_handle))

        container.addView(row)
    }

    private fun remove(index: Int) {
        if (index !in items.indices) {
            populate()
            return
        }
        items.removeAt(index)
        save()
        populate()
    }
}

private fun View.setPaddingBottom(pixels: Int) {
    setPadding(paddingLeft, paddingTop, paddingRight, pixels)
}
