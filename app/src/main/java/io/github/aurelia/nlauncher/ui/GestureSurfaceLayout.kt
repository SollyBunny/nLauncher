package io.github.aurelia.nlauncher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * The home screen's root view: a plain frame that treats a tap on itself as a click.
 *
 * Nothing on the home screen is clickable in the usual sense — the surface takes
 * gestures and passes taps through to the wallpaper behind it — but a view that
 * consumes touches still has to answer [performClick], or an accessibility service
 * that activates it gets silence and reads the whole home screen as one dead area.
 * Overriding it is what lets that activation reach the wallpaper, just as a sighted
 * user's tap does.
 */
class GestureSurfaceLayout(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    /** Where the last tap on the surface landed, forwarded to [onSurfaceClick]. */
    var tapX = 0f
    var tapY = 0f

    /** Run when the surface is activated, by a tap or by an accessibility click. */
    var onSurfaceClick: (() -> Unit)? = null

    /**
     * Consulted before a touch reaches a child of the surface. Returning true takes the
     * touch off the child, which is how a gesture that starts on a favorite or a widget
     * still reaches the screen instead of being swallowed by that view.
     */
    var onInterceptTouch: ((MotionEvent) -> Boolean)? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        onInterceptTouch?.invoke(ev) == true || super.onInterceptTouchEvent(ev)

    override fun performClick(): Boolean {
        super.performClick()
        onSurfaceClick?.invoke()
        return true
    }
}
