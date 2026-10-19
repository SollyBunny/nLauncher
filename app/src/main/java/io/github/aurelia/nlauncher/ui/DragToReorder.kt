package io.github.aurelia.nlauncher.ui

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import io.github.aurelia.nlauncher.R

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
                if (dragging) row.translationY = event.rawY - startRawY
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

    /** Move the dragged row to whichever side of its neighbours its centre landed on. */
    private fun drop() {
        val from = indexOfRow()
        if (from < 0 || from >= size()) {
            settle()
            return
        }

        val center = row.top + row.translationY + row.height / 2f
        var to = 0
        for (i in 0 until size()) {
            if (i == from) continue
            // Skip anything unexpected rather than letting a missing child crash.
            val other = childAt(i) ?: continue
            if (center > other.top + other.height / 2f) to++
        }

        if (to != from) onDrop(from, to)
        settle()
    }

    /** Undo the drag's visual effects and redraw. */
    private fun settle() {
        row.translationY = 0f
        row.elevation = 0f
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
