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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Resolve the launchable apps straight away, off the main thread, while the
        // UI inflates: the first time the search opens its list is already there.
        AppCatalog.init(this)
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
            stack.restore(restored)
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
        stack.pop()
    }

    /** The editable favorites. */
    fun pushEditFavorites() {
        stack.push(editFavorites)
    }

    /** The editable widgets. */
    fun pushEditWidgets() {
        stack.push(editWidgets)
    }

    /** The settings menu. */
    fun pushSettings() {
        stack.push(settings)
    }

    /** The gesture bindings. */
    fun pushGestures() {
        stack.push(gestures)
    }

    /** The settings that change how the launcher looks. */
    fun pushLookAndFeel() {
        stack.push(lookAndFeel)
    }

    /**
     * App search. When [hideAfter] is false the screen stays up after a pick — the
     * gesture that opens it that way is for browsing, and closing the list under the
     * user every time they try an app would make the next one a fresh search.
     *
     * Coming back from the app is the other half of that: the list was left open on
     * purpose, so the user should land on the home screen rather than be returned to
     * a search they finished with.
     */
    fun openSearch(
        hideAfter: Boolean = true,
        onPicked: (Intent) -> Unit,
    ) {
        search.onPicked =
            if (hideAfter) {
                { intent ->
                    back()
                    onPicked(intent)
                }
            } else {
                { intent ->
                    homeOnReturn = true
                    onPicked(intent)
                }
            }
        stack.push(search)
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
            EditFavoritesScreen.KEY -> editFavorites
            EditWidgetsScreen.KEY -> editWidgets
            SettingsScreen.KEY -> settings
            GesturesScreen.KEY -> gestures
            LookAndFeelScreen.KEY -> lookAndFeel
            // A restored search has nobody left to report a pick to.
            SearchScreen.KEY -> null
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
    }
}
