package io.github.aurelia.nlauncher

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets

/**
 * One full-screen UI of the launcher. Every screen owns its own layout and lives in
 * [LauncherActivity]'s window; the [UiStack] keeps exactly one of them on screen, so
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
}

/**
 * The screens [LauncherActivity] shows, most recent on top. The home screen sits at
 * the bottom and is never popped, so back always returns to the previous screen and
 * falls through to the platform once only the home screen is left.
 */
class UiStack(
    private val host: ViewGroup,
) {
    private val screens = mutableListOf<Screen>()

    /** The screen on top of the stack, or null while the activity shows nothing. */
    val top: Screen? get() = screens.lastOrNull()

    /** The keys of the screens on the stack, bottom first. */
    fun keys(): List<String> = screens.map { it.key }

    /** Show [screen] on top of the current one, taking the current one off the host. */
    fun push(screen: Screen) {
        if (top === screen) return
        deactivate(screens.lastOrNull())
        screens.add(screen)
        attach(screen)
    }

    /**
     * Drop the top screen and return to the one underneath. False when only the home
     * screen is left, which tells the activity to fall back to the platform default.
     */
    fun pop(): Boolean {
        if (screens.size <= 1) return false
        deactivate(screens.removeAt(screens.lastIndex))
        // The screen underneath was taken off the host when this one was pushed, so
        // it has to be put back before the user can see it again.
        attach(screens.last())
        return true
    }

    /** Return to the home screen, dropping everything stacked above it. */
    fun popToHome() {
        while (screens.size > 1) pop()
    }

    /** Put [screen] on the host and let it know it is the active one. */
    private fun attach(screen: Screen) {
        if (screen.view.parent !== host) {
            host.addView(
                screen.view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            // Ask for the window insets explicitly: a screen pushed later than the
            // home screen is attached to an already laid out window, and would
            // otherwise never be told where the system bars are.
            screen.view.requestApplyInsets()
        }
        screen.onActivated()
    }

    private fun deactivate(screen: Screen?) {
        if (screen == null) return
        screen.onDeactivated()
        host.removeView(screen.view)
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
 */
fun View.padForSystemBars(topExtraDp: Float = 0f) {
    val start = paddingStart
    val end = paddingEnd
    val top = paddingTop
    val bottom = paddingBottom
    val topExtra = (topExtraDp * resources.displayMetrics.density).toInt()

    setOnApplyWindowInsetsListener { view, insets ->
        val status: Int
        val nav: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            status = insets.getInsets(WindowInsets.Type.statusBars()).top
            nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        } else {
            @Suppress("DEPRECATION")
            status = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            nav = insets.systemWindowInsetBottom
        }
        view.setPaddingRelative(start, top + status + topExtra, end, bottom + nav)
        insets
    }
}
