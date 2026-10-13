package io.github.aurelia.nlauncher

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The home screen.
 *
 * The window is fully transparent and shows the system wallpaper behind it, which
 * means both static and live wallpapers are supported. Because the window carries
 * FLAG_SHOW_WALLPAPER, the platform also forwards touch events to the live wallpaper
 * (enabled by default via `LayoutParams.areWallpaperTouchEventsEnabled()`), so the
 * wallpaper stays interactive while we still receive gestures here.
 *
 * Editing happens right here too, via [editMode]: the same wallpaper is still behind
 * and the same [FavoriteViews] are used, only with a drag handle and a delete button
 * added around each one. There is no second copy of the home screen anywhere.
 */
class LauncherActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var favoritesContainer: LinearLayout
    private lateinit var editBar: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var clock: TextView
    private lateinit var wallpaperManager: WallpaperManager
    private lateinit var gestures: GestureDetector

    private val favorites = mutableListOf<FavoriteApp>()
    private var actions: Map<Gesture, GestureAction> = emptyMap()
    private var editMode = false

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            // Re-align to the next minute boundary so the time never lags.
            clockHandler.postDelayed(this, CLOCK_TICK_MS - System.currentTimeMillis() % CLOCK_TICK_MS)
        }
    }

    private val swipeMinDistance: Float by lazy { 48f * resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        root = findViewById(R.id.root)
        favoritesContainer = findViewById(R.id.favorites)
        editBar = findViewById(R.id.edit_bar)
        topBar = findViewById(R.id.top_bar)
        clock = findViewById(R.id.clock)
        clock.textSize = CLOCK_TEXT_SIZE
        wallpaperManager = WallpaperManager.getInstance(this)

        editBar.findViewById<View>(R.id.edit_done).setOnClickListener { setEditMode(false) }

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
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
                val gesture = if (abs(dx) > abs(dy)) {
                    if (dx > 0) Gesture.SWIPE_RIGHT else Gesture.SWIPE_LEFT
                } else {
                    if (dy > 0) Gesture.SWIPE_DOWN else Gesture.SWIPE_UP
                }
                trigger(gesture)
                return true
            }
        })

        // Only touches that no child (a favorite) consumed reach the root. Gestures
        // belong to the plain home screen, so they stay dormant while editing.
        root.setOnTouchListener { _, event ->
            if (!editMode) gestures.onTouchEvent(event)
            true
        }

        applyWindowInsets()
        registerBackCallback()

        setEditMode(intent.getBooleanExtra(EXTRA_EDIT_MODE, false))
        // The edit flag is a one-shot instruction, not activity state: drop it so a
        // later recreation does not silently drop back into edit mode.
        intent.removeExtra(EXTRA_EDIT_MODE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // The Home button re-delivers the HOME intent; always return to the plain
        // home screen from there.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            goHome()
            return
        }
        setEditMode(intent.getBooleanExtra(EXTRA_EDIT_MODE, false))
        intent.removeExtra(EXTRA_EDIT_MODE)
    }

    override fun onResume() {
        super.onResume()
        actions = LauncherConfig.gestureActions(this)
        populateFavorites()
        updateClock()
        clockHandler.post(clockTick)
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockTick)
    }

    /** Show the current time, honoring the user's 12/24-hour setting. */
    private fun updateClock() {
        clock.text = DateFormat.getTimeFormat(this).format(Date())
    }

    /** Back leaves edit mode first, then behaves like the platform default. */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        handleBack()
    }

    /**
     * Android 13+ routes predictive back through [OnBackInvokedDispatcher], not
     * [onBackPressed], so register a callback there as well.
     */
    private fun registerBackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { handleBack() }
        }
    }

    private fun handleBack() {
        if (editMode) {
            setEditMode(false)
        } else {
            finish()
        }
    }

    /**
     * Return to the plain home screen. Used when the system Home button is pressed,
     * which re-delivers the HOME intent: any open app picker is dismissed and edit
     * mode is left.
     */
    private fun goHome() {
        AppPicker.dismiss()
        root.visibility = View.VISIBLE
        setEditMode(false)
    }

    // ---- Edit mode -------------------------------------------------------

    private fun setEditMode(enabled: Boolean) {
        editMode = enabled
        editBar.visibility = if (enabled) View.VISIBLE else View.GONE
        // The edit bar owns the top of the screen while editing.
        clock.visibility = if (enabled) View.GONE else View.VISIBLE
        populateFavorites()
    }

    private fun populateFavorites() {
        favoritesContainer.removeAllViews()
        favorites.clear()
        favorites.addAll(LauncherConfig.favorites(this))

        if (editMode) {
            populateEditableFavorites()
        } else {
            populateLaunchableFavorites()
        }
    }

    private fun populateLaunchableFavorites() {
        for (favorite in favorites) {
            val view = FavoriteViews.create(layoutInflater, favoritesContainer, favorite, this)
            view.setOnClickListener { launch(favorite.intent) }
            view.setOnLongClickListener {
                setEditMode(true)
                true
            }
            favoritesContainer.addView(view)
        }
    }

    private fun populateEditableFavorites() {
        // The "+ Add favorite" row stays pinned at the top, above the favorites.
        val add = layoutInflater.inflate(R.layout.item_add_app, favoritesContainer, false)
        // Its + icon matches the favorites' icon size so the row lines up.
        val addLabel = add.findViewById<TextView>(R.id.add_app_label)
        val addIcon = add.findViewById<ImageView>(R.id.add_app_icon)
        val addIconSize = (addLabel.textSize * ICON_TEXT_SCALE).roundToInt()
        addIcon.layoutParams = addIcon.layoutParams.apply {
            width = addIconSize
            height = addIconSize
        }
        add.setOnClickListener {
            // Only the search UI should show while picking; hide the whole home
            // screen (favorites, edit bar) so nothing renders behind it.
            root.visibility = View.GONE
            AppPicker.show(
                this,
                onPicked = { intent ->
                    favorites.add(FavoriteApp(intent))
                    LauncherConfig.saveFavorites(this, favorites)
                    populateFavorites()
                },
                onDismiss = {
                    root.visibility = View.VISIBLE
                    populateFavorites()
                },
            )
        }
        favoritesContainer.addView(add)

        favorites.forEachIndexed { index, favorite ->
            val row = layoutInflater.inflate(R.layout.item_favorite_edit, favoritesContainer, false)

            // The very same view the home screen shows, just inert while editing.
            val preview = FavoriteViews.create(layoutInflater, null, favorite, this)
            preview.isClickable = false
            preview.isFocusable = false
            row.findViewById<FrameLayout>(R.id.favorite_preview).addView(
                preview,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )

            row.findViewById<View>(R.id.favorite_handle).setOnTouchListener(DragTouchListener(row))
            row.findViewById<View>(R.id.favorite_delete).setOnClickListener {
                favorites.removeAt(index)
                LauncherConfig.saveFavorites(this, favorites)
                populateFavorites()
            }
            favoritesContainer.addView(row)
        }
    }

    /** Drag a row by its handle to reorder it. */
    private inner class DragTouchListener(private val row: View) : View.OnTouchListener {

        private var startRawY = 0f
        private var dragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
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
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        row.parent?.requestDisallowInterceptTouchEvent(false)
                        dropFavorite(row)
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun dropFavorite(row: View) {
        // The first child is the "+ Add favorite" row; favorite rows follow it.
        val from = favoritesContainer.indexOfChild(row) - 1
        if (from < 0 || from >= favorites.size) {
            populateFavorites()
            return
        }

        val center = row.top + row.translationY + row.height / 2f
        var to = 0
        for (i in favorites.indices) {
            if (i == from) continue
            // Rows are laid out as [add] + favorites; skip anything unexpected
            // rather than letting a missing child crash the drag.
            val other = favoritesContainer.getChildAt(i + 1) ?: continue
            if (center > other.top + other.height / 2f) to++
        }

        if (to != from) {
            favorites.add(to, favorites.removeAt(from))
            LauncherConfig.saveFavorites(this, favorites)
        }
        populateFavorites()
    }

    // ---- Actions ---------------------------------------------------------

    private fun trigger(gesture: Gesture) {
        // While editing, gestures must not fire; the screen is busy.
        if (editMode) return
        when (val action = actions[gesture] ?: GestureAction.None) {
            is GestureAction.None -> Unit
            is GestureAction.OpenSettings -> openLauncherSettings()
            is GestureAction.ShowAppSearch -> openSearch()
            is GestureAction.OpenApp -> launch(action.intent)
        }
    }

    /**
     * Open the full-screen app search. The home screen is hidden while the picker
     * is up (it is transparent, so whatever is behind it would otherwise show), and
     * restored when the picker is dismissed.
     */
    private fun openSearch() {
        root.visibility = View.GONE
        AppPicker.show(
            this,
            onPicked = { intent -> launch(intent) },
            onDismiss = {
                root.visibility = View.VISIBLE
                populateFavorites()
            },
        )
    }

    private fun launch(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity to handle $intent", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to launch $intent", e)
        }
    }

    private fun openLauncherSettings() {
        startActivity(Intent(this, LauncherSettingsActivity::class.java))
    }

    private fun forwardTapToWallpaper(x: Float, y: Float) {
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        try {
            wallpaperManager.sendWallpaperCommand(
                root.windowToken,
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

    /** Keep the favorites clear of the navigation bar and the edit bar below the status bar. */
    private fun applyWindowInsets() {
        root.setOnApplyWindowInsetsListener { view, insets ->
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
            view.setPaddingRelative(view.paddingStart, 0, view.paddingEnd, nav)
            topBar.setPadding(
                topBar.paddingStart,
                dp(12f) + status,
                topBar.paddingEnd,
                topBar.paddingBottom,
            )
            editBar.setPadding(
                editBar.paddingStart,
                dp(12f) + status,
                editBar.paddingEnd,
                editBar.paddingBottom,
            )
            insets
        }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_EDIT_MODE = "io.github.aurelia.nlauncher.extra.EDIT_MODE"

        private const val TAG = "nLauncher"
        private const val CLOCK_TICK_MS = 60_000L

        /** Text size (sp) of the home screen clock. */
        private const val CLOCK_TEXT_SIZE = 48f
    }
}
