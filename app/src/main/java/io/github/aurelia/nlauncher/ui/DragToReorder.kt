package io.github.aurelia.nlauncher.ui

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import io.github.aurelia.nlauncher.R
import kotlin.math.abs

/**
 * Drag a row by its handle to reorder it, shared by the favorites and the widgets
 * editor so the two behave identically.
 *
 * A press on the handle is not a click, but it still has to go through
 * [View.performClick] or the view never reports one and an accessibility service
 * reads the handle as inert. That is the cheap half. The useful half is the pair of
 * move actions added to the row: reordering something you cannot drag is otherwise
 * impossible without sight of the screen.
 *
 * @param size how many rows there are.
 * @param childAt the view showing row [index], or null for one that is not in the list
 *   at all — the editors' "+ Add" row, which is not reorderable.
 * @param indexOfRow where this row currently sits, or anything out of range if it is
 *   not a row of this list.
 * @param onDrop the row's old and new index, when it actually moves. The rows are
 *   redrawn either way, so a drop that changes nothing still settles the row back.
 * @param onChanged redraw the list.
 */
internal class DragToReorder(
    private val row: View,
    private val size: () -> Int,
    private val childAt: (Int) -> View?,
    private val indexOfRow: () -> Int,
    private val onDrop: (from: Int, to: Int) -> Unit,
    private val onChanged: () -> Unit,
) : View.OnTouchListener {
    private var startRawY = 0f
    private var dragging = false

    /** Wire [handle] up to drag this row, and the row up to be moved without one. */
    fun attach(handle: View) {
        handle.setOnTouchListener(this)
        // So a move can hand focus to the row's new home, which is how the service
        // finds out it moved and reads out where it went.
        row.isFocusable = true
        row.accessibilityDelegate = moveActions()
    }

    override fun onTouch(
        v: View,
        event: MotionEvent,
    ): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                startRawY = event.rawY
                row.elevation = dp(8f).toFloat()
                row.parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) dragTo(event.rawY - startRawY)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    row.parent?.requestDisallowInterceptTouchEvent(false)
                    // A handle press is not a click, but the view still has to report
                    // one, or it reads as dead to anything driving it by accessibility.
                    v.performClick()
                    drop()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    row.parent?.requestDisallowInterceptTouchEvent(false)
                    // Cancelled: the row never really moved, so nothing is reordered —
                    // just put it back where it was drawn.
                    settle()
                }
                return true
            }
        }
        return false
    }

    /**
     * Follow the finger, but keep the row inside the list: it cannot be dragged above
     * the first row or below the last. The rows it passes move out of its way, as if
     * the list had already been reordered around it.
     */
    private fun dragTo(translation: Float) {
        val from = indexOfRow()
        if (from < 0 || from >= size()) {
            row.translationY = translation
            return
        }
        val clamped = clampTranslation(translation)
        row.translationY = clamped
        shiftRows(from, targetFor(from, clamped))
    }

    /** Keep the dragged row between the first and the last rows of the list. */
    private fun clampTranslation(translation: Float): Float {
        val first = childAt(0) ?: return translation
        val last = childAt(size() - 1) ?: return translation
        val top = (first.top - row.top).toFloat()
        val bottom = (last.top + last.height - row.height - row.top).toFloat()
        return if (top > bottom) 0f else translation.coerceIn(top, bottom)
    }

    /**
     * The slot the row would occupy at [translation], by which slot it is closest to.
     * Slots are where the row itself would be drawn after the move, not where the row
     * currently below it is, which also works when the rows have different heights.
     */
    private fun targetFor(
        from: Int,
        translation: Float,
    ): Int {
        val top = row.top + translation
        var target = from
        var distance = Float.MAX_VALUE
        for (i in 0 until size()) {
            val slot = slotTop(from, i) ?: continue
            val candidate = abs(top - slot)
            if (candidate < distance) {
                distance = candidate
                target = i
            }
        }
        return target
    }

    /** Where row [from] would be drawn if it ended up at index [to]. */
    private fun slotTop(
        from: Int,
        to: Int,
    ): Float? {
        val other = childAt(to) ?: return null
        // Moving down past the rows below: each one rises by the dragged row's
        // height, so the row sits against that row's bottom. Moving up, it takes the
        // place the row above had before that row was pushed down.
        return when {
            to > from -> (other.top + other.height - row.height).toFloat()
            else -> other.top.toFloat()
        }
    }

    /** Move every row the dragged one has passed to where it would be after the drop. */
    private fun shiftRows(
        from: Int,
        to: Int,
    ) {
        val distance = row.height.toFloat()
        for (i in 0 until size()) {
            if (i == from) continue
            val other = childAt(i) ?: continue
            other.translationY =
                when {
                    i > from && i <= to -> -distance
                    i >= to && i < from -> distance
                    else -> 0f
                }
        }
    }

    /** Move the dragged row to the slot its centre landed on. */
    private fun drop() {
        val from = indexOfRow()
        if (from < 0 || from >= size()) {
            settle()
            return
        }
        val to = targetFor(from, clampTranslation(row.translationY))
        if (to != from) onDrop(from, to)
        settle()
    }

    /** Undo the drag's visual effects and redraw. */
    private fun settle() {
        row.translationY = 0f
        row.elevation = 0f
        // The list is rebuilt by [onChanged], but clear the rows that moved out of the
        // way first: if the rebuild keeps any of them, they must not stay displaced.
        for (i in 0 until size()) {
            childAt(i)?.translationY = 0f
        }
        onChanged()
    }

    /** Move the row one place, from an accessibility action rather than a drag. */
    private fun move(delta: Int): Boolean {
        val from = indexOfRow()
        val to = from + delta
        if (from < 0 || from >= size() || to < 0 || to >= size()) return false
        onDrop(from, to)
        onChanged()
        // Hand focus to the row's new home: [onChanged] has just rebuilt the list, so
        // this is the row now showing the app that moved, and a service reads out
        // whatever takes focus — which is how it learns the row moved and where to.
        childAt(to)?.requestFocus()
        return true
    }

    /** Expose "move up" and "move down" on the row itself. */
    private fun moveActions() =
        object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: AccessibilityNodeInfo,
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                // The scroll actions are what an accessibility service drives to move
                // through a list, relabelled here to say what they do to this row.
                // There is no AndroidX in this app, so the dedicated move actions
                // AccessibilityNodeInfoCompat adds are not available.
                if (canMove(UP)) {
                    info.addAction(moveAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, R.string.move_up))
                }
                if (canMove(DOWN)) {
                    info.addAction(moveAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, R.string.move_down))
                }
            }

            override fun performAccessibilityAction(
                host: View,
                action: Int,
                args: Bundle?,
            ): Boolean =
                when (action) {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> move(UP)
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> move(DOWN)
                    else -> super.performAccessibilityAction(host, action, args)
                }
        }

    private fun moveAction(
        action: Int,
        nameRes: Int,
    ): AccessibilityNodeInfo.AccessibilityAction = AccessibilityNodeInfo.AccessibilityAction(action, row.context.getString(nameRes))

    private fun canMove(delta: Int): Boolean {
        val from = indexOfRow()
        val to = from + delta
        return from >= 0 && from < size() && to >= 0 && to < size()
    }

    private fun dp(value: Float): Int = (value * row.resources.displayMetrics.density).toInt()

    private companion object {
        /** Which way through the list a move goes; -1 is towards the top. */
        const val UP = -1
        const val DOWN = 1
    }
}
