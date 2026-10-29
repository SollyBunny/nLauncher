package io.github.aurelia.nlauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import io.github.aurelia.nlauncher.ui.Screen
import io.github.aurelia.nlauncher.ui.UiStack
import io.github.aurelia.nlauncher.ui.screens.EditFavoritesScreen
import io.github.aurelia.nlauncher.ui.screens.EditWidgetsScreen
import io.github.aurelia.nlauncher.ui.screens.GesturesScreen
import io.github.aurelia.nlauncher.ui.screens.HomeScreen
import io.github.aurelia.nlauncher.ui.screens.LookAndFeelScreen
import io.github.aurelia.nlauncher.ui.screens.SearchScreen
import io.github.aurelia.nlauncher.ui.screens.SettingsScreen

/**
 * The launcher, and the only activity it has.
 *
 * Every screen lives on a [UiStack]: the home screen at the bottom, everything else
 * pushed on top of it and popped off again. Only the top screen is attached to the
 * window, so exactly one UI is ever visible and back always returns to the previous
 * one — there is no "mode" that turns one screen into another.
 *
 * The window is transparent and carries [WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER],
 * so the system wallpaper shows behind the screens that do not paint their own
 * background, and the platform keeps forwarding touches to a live wallpaper.
 */
class LauncherActivity : Activity() {
    private lateinit var host: FrameLayout
    private lateinit var stack: UiStack

    // Built on first use: a cold start should not pay for screens nobody asked for.
    private val home by lazy { HomeScreen(this) }
    private val editFavorites by lazy { EditFavoritesScreen(this) }
    private val editWidgets by lazy { EditWidgetsScreen(this) }
    private val settings by lazy { SettingsScreen(this) }
    private val gestures by lazy { GesturesScreen(this) }
    private val lookAndFeel by lazy { LookAndFeelScreen(this) }

    /** One search screen, reused, so opening it again costs nothing to build. */
    private val search by lazy { SearchScreen(this) }

    /**
     * Set when a screen the user left open on purpose launched an app, so the resume
     * that follows going back to that app lands on the home screen. Cleared by the
     * resume itself, whatever the stack looks like by then.
     */
    private var homeOnReturn = false

    /** What each screen does with the app the search screen returns; see [onSearchResult]. */
    private val searchResults = mutableMapOf<String, (Intent) -> Unit>()

    /**
     * The key whose callback the open search screen will hand its pick to. This is the
     * only search state that has to outlive the activity: the callbacks themselves are
     * registered again by the screens as they are rebuilt — see [onSearchResult].
     */
    private var searchResultKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCatalogWatcher.init(this)
        setContentView(R.layout.activity_launcher)
        host = findViewById(R.id.root)

        // What the theme's windowShowWallpaper asks for, stated explicitly because
        // every screen in here sits in this one window: the wallpaper shows through
        // the transparent background, and live wallpapers stay interactive.
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)

        stack = UiStack(host)
        registerBackCallback()

        // A rotation (or anything else that recreates the activity) rebuilds the
        // stack, so the user lands on the same screens; otherwise start at home.
        val restored = savedInstanceState?.getStringArray(STATE_STACK)?.mapNotNull { screen(it) }
        if (restored.isNullOrEmpty()) {
            stack.push(home)
        } else {
            // The search screen is built while the stack is restored, so its result
            // ordering has to be back in place before it is activated.
            if (restored.lastOrNull()?.key == SearchScreen.KEY) {
                search.sortByUsage = savedInstanceState.getBoolean(STATE_SEARCH_SORT, false)
            }
            stack.restore(restored)
            // Building the restored screens registered their search callbacks again;
            // all that is left is which of them the open search screen belongs to.
            searchResultKey = savedInstanceState?.getString(STATE_SEARCH_RESULT)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // The Home button re-delivers the HOME intent to this singleTask: whatever is
        // open (the editor, the settings, the search) is left for the home screen.
        if (stack.top?.key == SearchScreen.KEY) searchResultKey = null
        stack.popToHome()
    }

    override fun onResume() {
        super.onResume()
        // Back from an app a screen left open on purpose. This has to happen before
        // the stack is resumed, so the home screen is the one that gets activated.
        if (homeOnReturn) {
            homeOnReturn = false
            stack.popToHome()
        }
        stack.onResumed()
    }

    override fun onPause() {
        stack.onPaused()
        super.onPause()
    }

    override fun onDestroy() {
        stack.destroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArray(STATE_STACK, stack.keys().toTypedArray())
        // Only the key is saved: the callbacks are rebuilt with their screens.
        if (stack.top?.key == SearchScreen.KEY) {
            outState.putString(STATE_SEARCH_RESULT, searchResultKey)
            outState.putBoolean(STATE_SEARCH_SORT, search.sortByUsage)
        }
    }

    // ---- Navigation ------------------------------------------------------

    /**
     * Close the screen on top.
     *
     * With only the home screen left there is nothing to close and nothing behind it,
     * so back is swallowed rather than finishing the launcher. Finishing the home
     * activity leaves the task empty, and the system answers that by starting the home
     * app again as a fresh activity — so the home screen tears itself down and builds
     * itself again, for a back press that was meant to do nothing.
     */
    fun back() {
        // Backing out of the search without a pick abandons the request.
        if (stack.top?.key == SearchScreen.KEY) searchResultKey = null
        stack.pop()
    }

    /**
     * Open the search screen; the app it returns goes to the callback registered for
     * [key] through [onSearchResult]. With [sortByUsage] the results are ordered by how
     * often they were picked until the user starts typing a query.
     */
    fun pushSearch(
        key: String,
        sortByUsage: Boolean = false,
    ) {
        searchResultKey = key
        search.sortByUsage = sortByUsage
        stack.push(search)
    }

    /**
     * Register what a screen does with an app picked in the search screen.
     *
     * The callback is looked up by [key] rather than held by the open screen, because
     * only the key is saved when the activity is recreated: rebuilding the screen
     * registers the callback again under the same key.
     */
    fun onSearchResult(
        key: String,
        callback: (Intent) -> Unit,
    ) {
        searchResults[key] = callback
    }

    /** The search screen picked [intent]: close it and hand the pick to its screen. */
    fun searchCompleted(intent: Intent) {
        val key = searchResultKey
        searchResultKey = null
        stack.pop()
        if (key != null) searchResults[key]?.invoke(intent)
    }

    fun pushEditFavorites() {
        stack.push(editFavorites)
    }

    fun pushEditWidgets() {
        stack.push(editWidgets)
    }

    fun pushSettings() {
        stack.push(settings)
    }

    fun pushGestures() {
        stack.push(gestures)
    }

    fun pushLookAndFeel() {
        stack.push(lookAndFeel)
    }

    fun launch(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.launch_activity_not_found_exception, intent), Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, getString(R.string.launch_security_exception, intent), Toast.LENGTH_SHORT).show()
        }
    }

    /** Rebuild a screen from its key, for when the activity is recreated. */
    private fun screen(key: String): Screen? =
        when (key) {
            HomeScreen.KEY -> home
            SearchScreen.KEY -> search
            EditFavoritesScreen.KEY -> editFavorites
            EditWidgetsScreen.KEY -> editWidgets
            SettingsScreen.KEY -> settings
            GesturesScreen.KEY -> gestures
            LookAndFeelScreen.KEY -> lookAndFeel
            else -> null
        }

    // ---- Back ------------------------------------------------------------

    /**
     * Only the pre-33 path: Android 13+ routes back through the
     * [OnBackInvokedDispatcher] callback registered in [registerBackCallback]. This
     * override is still needed for API 26-32, which is why lint's suggestion to move
     * to AndroidX's `OnBackPressedDispatcher` does not apply — this app has no
     * AndroidX, and the framework has no equivalent below API 33.
     */
    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        back()
    }

    /**
     * Android 13+ routes predictive back through [OnBackInvokedDispatcher], not
     * [onBackPressed], so register a callback there as well.
     */
    private fun registerBackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { back() }
        }
    }

    private companion object {
        private const val STATE_STACK = "io.github.aurelia.nlauncher.extra.STACK"
        private const val STATE_SEARCH_RESULT = "io.github.aurelia.nlauncher.extra.SEARCH_RESULT"
        private const val STATE_SEARCH_SORT = "io.github.aurelia.nlauncher.extra.SEARCH_SORT"
    }
}
