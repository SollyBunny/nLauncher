package io.github.aurelia.nlauncher.ui.screens

import android.app.WallpaperManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.TextView
import io.github.aurelia.nlauncher.FavoriteApp
import io.github.aurelia.nlauncher.Gesture
import io.github.aurelia.nlauncher.GestureAction
import io.github.aurelia.nlauncher.LauncherActivity
import io.github.aurelia.nlauncher.LauncherConfig
import io.github.aurelia.nlauncher.PackageWatcher
import io.github.aurelia.nlauncher.R
import io.github.aurelia.nlauncher.Widget
import io.github.aurelia.nlauncher.ui.FavoriteViews
import io.github.aurelia.nlauncher.ui.GestureSurfaceLayout
import io.github.aurelia.nlauncher.ui.Screen
import io.github.aurelia.nlauncher.ui.WidgetViews
import io.github.aurelia.nlauncher.ui.padForSystemBars
import java.util.Date
import kotlin.math.abs

/**
 * The home screen: the widgets (clock, date, ...) at the top, the favorites in the
 * bottom-left corner and the wallpaper behind them.
 *
 * Gestures live here and nowhere else, so they go quiet as soon as any other screen
 * is pushed on top of it — no "edit mode" check needed, since this screen is simply
 * not on screen while the editor is.
 */
class HomeScreen(
    private val activity: LauncherActivity,
) : Screen {
    override val key = KEY

    override val view: GestureSurfaceLayout =
        activity.layoutInflater.inflate(R.layout.screen_home, null, false) as GestureSurfaceLayout

    private val favoritesContainer: LinearLayout = view.findViewById(R.id.favorites)
    private val widgetsContainer: LinearLayout = view.findViewById(R.id.widgets)

    /** The widget views currently on screen, in order, so the tick can refresh them. */
    private val widgetViews = mutableListOf<Pair<Widget, TextView>>()

    private val wallpaperManager = WallpaperManager.getInstance(activity)

    /**
     * The favorites currently on screen, the package revision they were built at, and
     * the icon setting they were built with. Together they decide whether the column
     * still matches what is stored — see [populateFavorites].
     */
    private var favorites: List<FavoriteApp> = emptyList()
    private var renderedRevision = -1
    private var renderedIcons: String? = null
    private var actions: Map<Gesture, GestureAction> = emptyMap()

    private val gestures: GestureDetector

    private val swipeMinDistance: Float by lazy { SWIPE_MIN_DP * activity.resources.displayMetrics.density }
    private val minFlingVelocity: Float by lazy { ViewConfiguration.get(activity).scaledMinimumFlingVelocity.toFloat() }
    private val touchSlop: Float by lazy { ViewConfiguration.get(activity).scaledTouchSlop.toFloat() }

    /** Follows the finger so a short, fast flick still counts as a swipe. */
    private val velocity = VelocityTracker.obtain()
    private var touchStartX = 0f
    private var touchStartY = 0f

    /** Set once a gesture has fired, so one touch can only ever trigger one action. */
    private var gestureFired = false

    private val clockHandler = Handler(Looper.getMainLooper())
    private var clockTicking = false
    private val clockTick =
        object : Runnable {
            override fun run() {
                updateWidgets()
                // Re-align to the next minute boundary so the time never lags.
                clockHandler.postDelayed(this, CLOCK_TICK_MS - System.currentTimeMillis() % CLOCK_TICK_MS)
            }
        }

    init {
        gestures =
            GestureDetector(
                activity,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean = true

                    /**
                     * A tap on empty space. Legacy live wallpapers react to COMMAND_TAP
                     * (newer ones get the raw touch forwarded by the platform already).
                     */
                    override fun onSingleTapUp(e: MotionEvent): Boolean {
                        forwardTapToWallpaper(e.x, e.y)
                        return true
                    }

                    override fun onLongPress(e: MotionEvent) {
                        gestureFired = true
                        trigger(Gesture.HOLD)
                    }

                    // No onFling: a fling needs the finger to be moving fast at the
                    // moment it is released, so it missed deliberate swipes and read a
                    // slow one as nothing. The direction is worked out from where the
                    // finger ended up instead — see trackSwipe().
                },
            )

        // Only touches that no child (a favorite) consumed reach this screen, which is
        // what keeps the gestures off the favorites themselves. A touch here is a
        // gesture or a tap on the wallpaper, never a click on a view, so the surface
        // itself answers performClick: that is what lets an accessibility service
        // reach the wallpaper the same way a tap does.
        view.onSurfaceClick = { forwardTapToWallpaper(view.tapX, view.tapY) }
        view.setOnTouchListener { v, event ->
            gestures.onTouchEvent(event)
            trackSwipe(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                view.tapX = event.x
                view.tapY = event.y
                // A swipe already claimed this touch; a tap is what is left.
                if (!gestureFired) v.performClick()
            }
            true
        }

        // Keep the favorites clear of the navigation bar, and the widgets below the
        // status bar with their own breathing room on top.
        view.padForSystemBars(topExtraDp = BAR_PADDING_DP)
    }

    override fun onActivated() {
        actions = LauncherConfig.gestureActions(activity)

        // The widgets are rebuilt every time (cheap, and their text moves with the
        // clock); the favorites only when they have actually changed — see below.
        populateWidgets()
        updateWidgets()
        populateFavorites()

        // Only ever one tick, however many times this screen is activated: a push and
        // the resume that follows it are two activations of the same thing. Only the
        // clock and the alarm need one — the date does not move by the minute, and
        // the alarm has to be re-read so it clears once it has gone off.
        if (widgetViews.any { it.first == Widget.CLOCK || it.first == Widget.ALARM }) {
            if (!clockTicking) {
                clockTicking = true
                clockHandler.post(clockTick)
            }
        } else if (clockTicking) {
            clockTicking = false
            clockHandler.removeCallbacks(clockTick)
        }
    }

    override fun onDeactivated() {
        if (clockTicking) {
            clockTicking = false
            clockHandler.removeCallbacks(clockTick)
        }
    }

    override fun onDestroyed() {
        // Native allocation, and the whole activity is on its way out with it.
        velocity.recycle()
    }

    /** Rebuild the widget column from the stored order. */
    private fun populateWidgets() {
        widgetsContainer.removeAllViews()
        widgetViews.clear()
        for (widget in LauncherConfig.widgets(activity)) {
            val view = WidgetViews.create(activity, widget)
            // Tapping a widget opens the clock app. The click is claimed by the
            // widget, so it does not also reach the surface and land on the wallpaper.
            view.setOnClickListener { openClock() }
            widgetsContainer.addView(
                view,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            widgetViews.add(widget to view)
        }
    }

    /** Refresh every widget on screen; each one formats its own text. */
    private fun updateWidgets() {
        val now = Date()
        for ((widget, view) in widgetViews) view.text = WidgetViews.text(activity, widget, now)
    }

    /**
     * Rebuild the favorites column, but only when what is on screen no longer matches
     * what is stored. A launcher redraws this screen on every Home press, and
     * inflating a row per favorite every time is the most expensive thing it does —
     * for a list that almost never changes between two presses.
     *
     * The package revision is part of the check because rows only ask [Icons] for
     * their icon as they are created: when a package changes, the icon cache is
     * dropped underneath them, and the column has to be built again to pick the new
     * icons up. The icon setting is part of it for the same reason: a row only finds
     * out whether it shows an icon as it is built, so changing the setting has to
     * rebuild the column or the rows keep drawing what they were built with.
     */
    private fun populateFavorites() {
        val stored = LauncherConfig.favorites(activity)
        val revision = PackageWatcher.revision
        val icons = iconSetting()
        if (revision == renderedRevision &&
            icons == renderedIcons &&
            favorites.map { it.key } == stored.map { it.key }
        ) {
            return
        }
        favorites = stored
        renderedRevision = revision
        renderedIcons = icons

        favoritesContainer.removeAllViews()
        for (favorite in favorites) {
            val row = FavoriteViews.create(activity.layoutInflater, favoritesContainer, favorite, activity)
            row.setOnClickListener { activity.launch(favorite.intent) }
            row.setOnLongClickListener {
                activity.pushEditFavorites()
                true
            }
            favoritesContainer.addView(row)
        }
    }

    /** What the rows would be built with icon-wise, as one comparable value. */
    private fun iconSetting(): String = "${LauncherConfig.iconsShown(activity)}/${LauncherConfig.iconPack(activity)}"

    private fun trigger(gesture: Gesture) {
        when (val action = actions[gesture] ?: GestureAction.None) {
            is GestureAction.None -> Unit
            is GestureAction.OpenSettings -> activity.pushSettings()
            is GestureAction.ShowAppSearch -> activity.openSearch(false) { activity.launch(it) }
            is GestureAction.OpenApp -> activity.launch(action.intent)
        }
    }

    /**
     * Open the clock app, the way tapping the clock in the status bar does.
     *
     * There is no public "show the clock" action, so the clock app is found through
     * the alarm action — it is the app that answers it — and opened at its launcher
     * activity; the alarm screen is the fallback for one without a launcher activity.
     */
    private fun openClock() {
        val pm = activity.packageManager
        val alarm = Intent(AlarmClock.ACTION_SHOW_ALARMS)
        val clockPackage = pm.resolveActivity(alarm, 0)?.activityInfo?.packageName
        activity.launch(clockPackage?.let { pm.getLaunchIntentForPackage(it) } ?: alarm)
    }

    /**
     * Works out a swipe from where the finger ended up, rather than from how fast it
     * was moving when it was let go. A swipe counts once it has travelled far enough,
     * however slowly it was made and whatever the finger did in between: pausing
     * halfway, or hanging on at the end for a few seconds, no longer turns a swipe
     * into a hold (the [GestureDetector] drops the long press as soon as the finger
     * moves) or into nothing at all. A short throw still counts too, since that is a
     * swipe too.
     */
    private fun trackSwipe(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureFired = false
                touchStartX = event.x
                touchStartY = event.y
                velocity.clear()
                velocity.addMovement(event)
            }
            MotionEvent.ACTION_MOVE -> velocity.addMovement(event)
            MotionEvent.ACTION_UP -> {
                velocity.addMovement(event)
                if (!gestureFired) {
                    velocity.computeCurrentVelocity(1000)
                    val dx = event.x - touchStartX
                    val dy = event.y - touchStartY
                    // A swipe is a gesture like any other, so it claims the touch: one
                    // finger-down must never trigger two things, here or in the
                    // click the touch listener sends on the way out.
                    if (isSwipe(dx, dy) || isFlick(dx, dy)) {
                        gestureFired = true
                        trigger(direction(dx, dy))
                    }
                }
                velocity.clear()
            }
            MotionEvent.ACTION_CANCEL -> velocity.clear()
        }
    }

    /** Far enough to be a deliberate swipe, on whichever axis it went down. */
    private fun isSwipe(
        dx: Float,
        dy: Float,
    ): Boolean = maxOf(abs(dx), abs(dy)) >= swipeMinDistance

    /** Too short to be deliberate, but thrown fast enough to mean it anyway. */
    private fun isFlick(
        dx: Float,
        dy: Float,
    ): Boolean =
        maxOf(abs(velocity.xVelocity), abs(velocity.yVelocity)) >= minFlingVelocity &&
            maxOf(abs(dx), abs(dy)) > touchSlop

    private fun direction(
        dx: Float,
        dy: Float,
    ): Gesture =
        if (abs(dx) > abs(dy)) {
            if (dx > 0) Gesture.SWIPE_RIGHT else Gesture.SWIPE_LEFT
        } else {
            if (dy > 0) Gesture.SWIPE_DOWN else Gesture.SWIPE_UP
        }

    private fun forwardTapToWallpaper(
        x: Float,
        y: Float,
    ) {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        try {
            wallpaperManager.sendWallpaperCommand(
                view.windowToken,
                WallpaperManager.COMMAND_TAP,
                location[0] + x.toInt(),
                location[1] + y.toInt(),
                0,
                null,
            )
        } catch (e: Exception) {
            // No live wallpaper is listening; nothing to do, but keep the reason.
            Log.d("Launcher", "Wallpaper command failed", e)
        }
    }

    companion object {
        const val KEY = "home"

        private const val CLOCK_TICK_MS = 60_000L
        private const val SWIPE_MIN_DP = 48f
        private const val BAR_PADDING_DP = 12f
    }
}
