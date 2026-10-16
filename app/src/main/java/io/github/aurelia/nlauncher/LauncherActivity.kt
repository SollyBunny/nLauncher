package io.github.aurelia.nlauncher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedDispatcher
import io.github.aurelia.nlauncher.ui.Screen
import io.github.aurelia.nlauncher.ui.UiStack
import io.github.aurelia.nlauncher.ui.screens.EditFavoritesScreen
import io.github.aurelia.nlauncher.ui.screens.GesturesScreen
import io.github.aurelia.nlauncher.ui.screens.HomeScreen
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
    private val settings by lazy { SettingsScreen(this) }
    private val gestures by lazy { GesturesScreen(this) }

    /** One search screen, reused, so opening it again costs nothing to build. */
    private val search by lazy { SearchScreen(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            restored.forEach { stack.push(it) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // The Home button re-delivers the HOME intent to this singleTask: whatever is
        // open (the editor, the settings, the search) is left for the home screen.
        stack.popToHome()
    }

    override fun onResume() {
        super.onResume()
        stack.top?.onActivated()
    }

    override fun onPause() {
        stack.top?.onDeactivated()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArray(STATE_STACK, stack.keys().toTypedArray())
    }

    // ---- Navigation ------------------------------------------------------

    /** Close the screen on top, or leave the launcher when only home is left. */
    fun back() {
        if (!stack.pop()) finish()
    }

    /** The editable favorites. */
    fun pushEditFavorites() {
        stack.push(editFavorites)
    }

    /** The settings menu. */
    fun pushSettings() {
        stack.push(settings)
    }

    /** The gesture bindings. */
    fun pushGestures() {
        stack.push(gestures)
    }

    /**
     * The app search, on top of whatever asked for it. [onPicked] runs once the search
     * has closed itself, so the screen the user returns to is the caller again.
     */
    fun openSearch(onPicked: (Intent) -> Unit) {
        search.onPicked = onPicked
        stack.push(search)
    }

    fun launch(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w("Launcher", "No activity to handle $intent", e)
        } catch (e: SecurityException) {
            Log.w("Launcher", "Not allowed to launch $intent", e)
        }
    }

    /** Rebuild a screen from its key, for when the activity is recreated. */
    private fun screen(key: String): Screen? =
        when (key) {
            HomeScreen.KEY -> home
            EditFavoritesScreen.KEY -> editFavorites
            SettingsScreen.KEY -> settings
            GesturesScreen.KEY -> gestures
            // A restored search has nobody left to report a pick to.
            SearchScreen.KEY -> search.also { it.onPicked = {} }
            else -> null
        }

    // ---- Back ------------------------------------------------------------

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
    }
}
