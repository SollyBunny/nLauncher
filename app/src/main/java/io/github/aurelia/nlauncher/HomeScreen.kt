package io.github.aurelia.nlauncher

import android.app.WallpaperManager
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date
import kotlin.math.abs

/**
 * The home screen: the clock, the favorites in the bottom-left corner and the
 * wallpaper behind them.
 *
 * Gestures live here and nowhere else, so they go quiet as soon as any other screen
 * is pushed on top of it — no "edit mode" check needed, since this screen is simply
 * not on screen while the editor is.
 */
class HomeScreen(
    private val activity: LauncherActivity,
) : Screen {
    override val key = KEY

    override val view: FrameLayout = activity.layoutInflater.inflate(R.layout.screen_home, null, false) as FrameLayout

    private val favoritesContainer: LinearLayout = view.findViewById(R.id.favorites)
    private val clock: TextView = view.findViewById(R.id.clock)

    private val wallpaperManager = WallpaperManager.getInstance(activity)

    private val favorites = mutableListOf<FavoriteApp>()
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
                updateClock()
                // Re-align to the next minute boundary so the time never lags.
                clockHandler.postDelayed(this, CLOCK_TICK_MS - System.currentTimeMillis() % CLOCK_TICK_MS)
            }
        }

    init {
        clock.textSize = CLOCK_TEXT_SIZE

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
        // what keeps the gestures off the favorites themselves.
        view.setOnTouchListener { _, event ->
            gestures.onTouchEvent(event)
            trackSwipe(event)
            true
        }

        // Keep the favorites clear of the navigation bar, and the clock below the status
        // bar with its own breathing room on top.
        view.padForSystemBars(topExtraDp = BAR_PADDING_DP)
    }

    override fun onActivated() {
        actions = LauncherConfig.gestureActions(activity)
        populateFavorites()
        updateClock()
        // This screen is activated both when it is pushed and when the activity
        // resumes while it is on top, so only ever start one tick.
        if (!clockTicking) {
            clockTicking = true
            clockHandler.post(clockTick)
        }
    }

    override fun onDeactivated() {
        if (clockTicking) {
            clockTicking = false
            clockHandler.removeCallbacks(clockTick)
        }
    }

    /** Show the current time, honoring the user's 12/24-hour setting. */
    private fun updateClock() {
        clock.text = DateFormat.getTimeFormat(activity).format(Date())
    }

    private fun populateFavorites() {
        favoritesContainer.removeAllViews()
        favorites.clear()
        favorites.addAll(LauncherConfig.favorites(activity))

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

    private fun trigger(gesture: Gesture) {
        when (val action = actions[gesture] ?: GestureAction.None) {
            is GestureAction.None -> Unit
            is GestureAction.OpenSettings -> activity.pushSettings()
            is GestureAction.ShowAppSearch -> activity.openSearch { activity.launch(it) }
            is GestureAction.OpenApp -> activity.launch(action.intent)
        }
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
                    if (isSwipe(dx, dy) || isFlick(dx, dy)) trigger(direction(dx, dy))
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
            Log.d(APP_NAME, "Wallpaper command failed", e)
        }
    }

    companion object {
        const val KEY = "home"

        private const val CLOCK_TICK_MS = 60_000L
        private const val SWIPE_MIN_DP = 48f
        private const val BAR_PADDING_DP = 12f

        /** Text size (sp) of the home screen clock. */
        private const val CLOCK_TEXT_SIZE = 48f
    }
}
