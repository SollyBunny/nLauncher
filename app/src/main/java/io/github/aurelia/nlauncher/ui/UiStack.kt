package io.github.aurelia.nlauncher.ui

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets

/**
 * One full-screen UI of the launcher. Every screen owns its own layout and lives in
 * [io.github.aurelia.nlauncher.LauncherActivity]'s window; the [UiStack] keeps exactly one of them on screen, so
 * no two screens can ever render at the same time.
 */
interface Screen {
    /** Stable identifier, used to restore the stack when the activity is recreated. */
    val key: String

    /** This screen's root view. */
    val view: View

    /**
     * The screen became the active one: it was pushed on top of the stack, or it was
     * already on top when the activity resumed. Refresh what it shows here.
     */
    fun onActivated() {}

    /**
     * The screen stopped being the active one: another screen covers it, or the
     * activity paused. Stop timers and let go of the keyboard here.
     */
    fun onDeactivated() {}

    /**
     * The activity is going away for good — not merely covered or paused. Let go of
     * anything that outlives it (native resources, callbacks into a dying screen).
     */
    fun onDestroyed() {}
}

/**
 * The screens [io.github.aurelia.nlauncher.LauncherActivity] shows, most recent on top. The home screen sits at
 * the bottom and is never popped, so back always returns to the previous screen and
 * falls through to the platform once only the home screen is left.
 */
class UiStack(
    private val host: ViewGroup,
) {
    private val screens = mutableListOf<Screen>()

    private var active: Screen? = null

    /** The screen on top of the stack, or null while the activity shows nothing. */
    val top: Screen? get() = screens.lastOrNull()

    /** The keys of the screens on the stack, bottom first. */
    fun keys(): List<String> = screens.map { it.key }

    /** Show [screen] on top of the current one, taking the current one off the host. */
    fun push(screen: Screen) {
        if (top === screen) return
        stop(screens.lastOrNull())
        screens.add(screen)
        start(screen)
    }

    /**
     * Drop the top screen and return to the one underneath. False when only the home
     * screen is left, which tells the activity there is nothing behind this one.
     */
    fun pop(): Boolean {
        if (screens.size <= 1) return false
        stop(screens.removeAt(screens.lastIndex))
        start(screens.last())
        return true
    }

    fun restore(stack: List<Screen>) {
        stop(top)
        screens.clear()
        screens.addAll(stack)
        start(stack.lastOrNull())
    }

    /** Return to the home screen, dropping everything stacked above it. */
    fun popToHome() {
        while (screens.size > 1) pop()
    }

    /** The activity resumed: the top screen becomes active, unless it already is. */
    fun onResumed() {
        start(top)
    }

    /**
     * The activity paused: whatever is active stops being active. Its view stays on
     * the host — the window is off screen either way, and taking it off and putting
     * it back would throw the layout away for nothing.
     */
    fun onPaused() {
        stop(top, detach = false)
    }

    /** The activity is going away for good: tell every screen, however deep it is. */
    fun destroy() {
        stop(top)
        for (screen in screens) screen.onDestroyed()
        active = null
        screens.clear()
    }

    /**
     * Put [screen] on the host and let it know it is the active one — unless it has
     * been told already, which is what keeps a cold start from doing this screen's
     * work twice over (once through the push, once through the resume).
     */
    private fun start(screen: Screen?) {
        if (screen == null || screen === active) return
        if (screen.view.parent !== host) {
            host.addView(
                screen.view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            // Ask for the window insets explicitly: a screen pushed later than the home
            // screen is attached to an already laid out window, and would otherwise
            // never be told where the system bars are.
            screen.view.requestApplyInsets()
        }
        active = screen
        screen.onActivated()
    }

    /**
     * Let [screen] know it is not the active one any more and take it off the host,
     * unless [detach] says it is only paused rather than covered or closed.
     */
    private fun stop(
        screen: Screen?,
        detach: Boolean = true,
    ) {
        if (screen == null) return
        if (screen === active) {
            active = null
            screen.onDeactivated()
        }
        if (detach) host.removeView(screen.view)
    }
}

/**
 * Keep this view's contents clear of the system bars. The launcher window is
 * translucent and drawn edge to edge, so every screen lays itself out against the
 * insets itself.
 *
 * Apply this to a screen's root view and to nothing else: [topExtraDp] is the room
 * the screen wants below the status bar (for a bar that carries its own padding).
 * Bars *inside* a screen must keep their plain layout padding, otherwise the same
 * inset is counted twice and they drift down the screen.
 *
 * [ime] also makes room for the keyboard, for the one screen that opens one. It is
 * sized by the system and reported as an inset of its own, so the room it needs is
 * read from the window rather than guessed at. The window will not resize for it
 * (the activity handles `screenSize` itself, so the system never shrinks it), which
 * is exactly why this has to be done here. Below API 30 there is no separate IME
 * inset and the window is resized for the keyboard instead.
 */
fun View.padForSystemBars(
    topExtraDp: Float = 0f,
    ime: Boolean = false,
) {
    val start = paddingStart
    val end = paddingEnd
    val top = paddingTop
    val bottom = paddingBottom
    val topExtra = (topExtraDp * resources.displayMetrics.density).toInt()

    setOnApplyWindowInsetsListener { view, insets ->
        val status: Int
        val nav: Int
        val keyboard: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            status = insets.getInsets(WindowInsets.Type.statusBars()).top
            nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            keyboard = if (ime) insets.getInsets(WindowInsets.Type.ime()).bottom else 0
        } else {
            @Suppress("DEPRECATION")
            status = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            nav = insets.systemWindowInsetBottom
            keyboard = 0
        }
        // The keyboard is measured from the bottom of the window, so when it is up its
        // inset already covers the navigation bar it sits over; only one of the two is
        // the room that is actually taken.
        view.setPaddingRelative(start, top + status + topExtra, end, bottom + maxOf(nav, keyboard))
        insets
    }
}
