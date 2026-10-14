package io.github.aurelia.nlauncher

import android.app.WallpaperManager
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
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
                        trigger(Gesture.HOLD)
                    }

                    override fun onFling(
                        e1: MotionEvent?,
                        e2: MotionEvent,
                        velocityX: Float,
                        velocityY: Float,
                    ): Boolean {
                        val start = e1 ?: return false
                        val dx = e2.x - start.x
                        val dy = e2.y - start.y
                        if (abs(dx) < swipeMinDistance && abs(dy) < swipeMinDistance) return false
                        val gesture =
                            if (abs(dx) > abs(dy)) {
                                if (dx > 0) Gesture.SWIPE_RIGHT else Gesture.SWIPE_LEFT
                            } else {
                                if (dy > 0) Gesture.SWIPE_DOWN else Gesture.SWIPE_UP
                            }
                        trigger(gesture)
                        return true
                    }
                },
            )

        // Only touches that no child (a favorite) consumed reach this screen, which is
        // what keeps the gestures off the favorites themselves.
        view.setOnTouchListener { _, event ->
            gestures.onTouchEvent(event)
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
            Log.d(TAG, "Wallpaper command failed", e)
        }
    }

    companion object {
        const val KEY = "home"

        private const val TAG = "nLauncher"
        private const val CLOCK_TICK_MS = 60_000L
        private const val SWIPE_MIN_DP = 48f
        private const val BAR_PADDING_DP = 12f

        /** Text size (sp) of the home screen clock. */
        private const val CLOCK_TEXT_SIZE = 48f
    }
}