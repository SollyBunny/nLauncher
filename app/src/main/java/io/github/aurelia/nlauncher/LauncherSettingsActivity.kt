package io.github.aurelia.nlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedDispatcher

/**
 * nLauncher's own settings: a menu that opens the gesture bindings. Editing the
 * favorites is not done here at all — that happens in [LauncherActivity]'s edit
 * mode, on the actual home screen, so there is only one copy of that UI.
 */
class LauncherSettingsActivity : Activity() {

    private enum class Screen { MENU, GESTURES }

    private lateinit var content: FrameLayout
    private lateinit var settingsRoot: View
    private lateinit var backButton: ImageView
    private lateinit var headerTitle: TextView

    private var screen = Screen.MENU

    private lateinit var gesturesList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        content = findViewById(R.id.settings_content)
        settingsRoot = findViewById(R.id.settings_root)
        backButton = findViewById(R.id.settings_back)
        headerTitle = findViewById(R.id.settings_title)

        backButton.setOnClickListener { showScreen(Screen.MENU) }
        registerBackCallback()
        // Keep the user on the same screen across recreation (rotation, etc.).
        val restored = savedInstanceState?.getString(STATE_SCREEN)
            ?.let { runCatching { Screen.valueOf(it) }.getOrNull() }
            ?: Screen.MENU
        showScreen(restored)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SCREEN, screen.name)
    }

    /** Back returns to the menu first, then behaves like the platform default. */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        handleBack()
    }

    private fun handleBack() {
        if (screen != Screen.MENU) {
            showScreen(Screen.MENU)
        } else {
            finish()
        }
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

    private fun showScreen(target: Screen) {
        screen = target
        backButton.visibility = if (target == Screen.MENU) View.GONE else View.VISIBLE
        headerTitle.setText(
            when (target) {
                Screen.MENU -> R.string.app_name
                Screen.GESTURES -> R.string.menu_gestures
            },
        )

        content.removeAllViews()
        val panel = when (target) {
            Screen.MENU -> buildMenuView()
            Screen.GESTURES -> buildGesturesView()
        }
        content.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    // ---- Menu ------------------------------------------------------------

    private fun buildMenuView(): View {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(menuRow(R.string.menu_favorites) { openFavoritesEditor() })
        list.addView(menuRow(R.string.menu_gestures) { showScreen(Screen.GESTURES) })
        return scrollable(list)
    }

    private fun menuRow(titleRes: Int, onClick: () -> Unit): View {
        val row = layoutInflater.inflate(R.layout.item_menu, null, false)
        row.findViewById<TextView>(R.id.menu_title).setText(titleRes)
        row.setOnClickListener { onClick() }
        return row
    }

    /**
     * Hands over to the home screen's edit mode. Because [LauncherActivity] is a
     * singleTask home activity, it comes back to the front and picks the flag up in
     * onNewIntent, so the user genuinely edits the real home screen.
     */
    private fun openFavoritesEditor() {
        startActivity(
            Intent(this, LauncherActivity::class.java)
                .putExtra(LauncherActivity.EXTRA_EDIT_MODE, true),
        )
    }

    // ---- Gestures --------------------------------------------------------

    private fun buildGesturesView(): View {
        gesturesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rebuildGestureRows()
        return scrollable(gesturesList)
    }

    private fun rebuildGestureRows() {
        gesturesList.removeAllViews()
        for (gesture in Gesture.entries) {
            val row = layoutInflater.inflate(R.layout.item_gesture, gesturesList, false)
            row.findViewById<TextView>(R.id.gesture_name).text = getString(
                when (gesture) {
                    Gesture.HOLD -> R.string.gesture_hold
                    Gesture.SWIPE_UP -> R.string.gesture_swipe_up
                    Gesture.SWIPE_DOWN -> R.string.gesture_swipe_down
                    Gesture.SWIPE_LEFT -> R.string.gesture_swipe_left
                    Gesture.SWIPE_RIGHT -> R.string.gesture_swipe_right
                },
            )
            row.findViewById<TextView>(R.id.gesture_action).text =
                actionSummary(LauncherConfig.gestureAction(this, gesture))
            row.setOnClickListener { chooseAction(gesture) }
            gesturesList.addView(row)
        }
    }

    private fun actionSummary(action: GestureAction): CharSequence = when (action) {
        is GestureAction.None -> getString(R.string.action_none)
        is GestureAction.OpenSettings -> getString(R.string.action_open_settings)
        is GestureAction.ShowSearchBar -> getString(R.string.action_show_search)
        is GestureAction.OpenApp -> getString(
            R.string.action_open_app_named,
            FavoriteApp(action.intent).resolve(this).label,
        )
    }

    private fun chooseAction(gesture: Gesture) {
        val items = arrayOf(
            getString(R.string.action_none),
            getString(R.string.action_open_settings),
            getString(R.string.action_open_app),
            getString(R.string.action_show_search),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_choose_action)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> applyGesture(gesture, GestureAction.None)
                    1 -> applyGesture(gesture, GestureAction.OpenSettings)
                    2 -> {
                        // Only the search UI should show while picking: hide the
                        // entire settings screen, header included, so nothing
                        // renders behind the transparent picker.
                        settingsRoot.visibility = View.GONE
                        AppPicker.show(
                            this,
                            onPicked = { intent ->
                                applyGesture(gesture, GestureAction.OpenApp(intent))
                            },
                            onDismiss = { settingsRoot.visibility = View.VISIBLE },
                        )
                    }
                    3 -> applyGesture(gesture, GestureAction.ShowSearchBar)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyGesture(gesture: Gesture, action: GestureAction) {
        val candidate = LauncherConfig.gestureActions(this).toMutableMap()
        candidate[gesture] = action

        // Never allow a state with no way back into these settings.
        if (!LauncherConfig.hasSettingsAccess(candidate.values)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.error_keep_settings_title)
                .setMessage(R.string.error_keep_settings)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        LauncherConfig.saveGestureAction(this, gesture, action)
        rebuildGestureRows()
    }

    // ---- Shared helpers --------------------------------------------------

    private fun scrollable(inner: View): View = ScrollView(this).apply {
        isFillViewport = true
        addView(
            inner,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private companion object {
        private const val STATE_SCREEN = "io.github.aurelia.nlauncher.extra.SCREEN"
    }
}
