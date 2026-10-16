package io.github.aurelia.nlauncher.ui.screens

import android.app.AlertDialog
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.aurelia.nlauncher.FavoriteApp
import io.github.aurelia.nlauncher.Favorites
import io.github.aurelia.nlauncher.Gesture
import io.github.aurelia.nlauncher.GestureAction
import io.github.aurelia.nlauncher.LauncherActivity
import io.github.aurelia.nlauncher.LauncherConfig
import io.github.aurelia.nlauncher.R
import io.github.aurelia.nlauncher.ui.Screen
import io.github.aurelia.nlauncher.ui.padForSystemBars

/**
 * The gesture bindings, one row per gesture showing the action bound to it. Binding
 * "Open app" pushes the app search on top of this screen.
 *
 * Shares [R.layout.screen_settings] with [SettingsScreen]: same header, different
 * content panel and title.
 */
class GesturesScreen(
    private val activity: LauncherActivity,
) : Screen {
    override val key = KEY

    override val view: View = activity.layoutInflater.inflate(R.layout.screen_settings, null, false)

    private val gesturesList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    init {
        view.findViewById<View>(R.id.settings_back).setOnClickListener { activity.back() }
        view.findViewById<TextView>(R.id.settings_title).setText(R.string.menu_gestures)
        view.padForSystemBars()

        val content: FrameLayout = view.findViewById(R.id.settings_content)
        content.addView(scrollableRows(activity, gesturesList))
    }

    override fun onActivated() {
        rebuildGestureRows()
    }

    private fun rebuildGestureRows() {
        gesturesList.removeAllViews()
        for (gesture in Gesture.entries) {
            val row = activity.layoutInflater.inflate(R.layout.item_gesture, gesturesList, false)
            row.findViewById<TextView>(R.id.gesture_name).setText(gesture.label())
            row.findViewById<TextView>(R.id.gesture_action).text =
                actionSummary(LauncherConfig.gestureAction(activity, gesture))
            row.setOnClickListener { chooseAction(gesture) }
            gesturesList.addView(row)
        }
    }

    private fun Gesture.label(): Int =
        when (this) {
            Gesture.HOLD -> R.string.gesture_hold
            Gesture.SWIPE_UP -> R.string.gesture_swipe_up
            Gesture.SWIPE_DOWN -> R.string.gesture_swipe_down
            Gesture.SWIPE_LEFT -> R.string.gesture_swipe_left
            Gesture.SWIPE_RIGHT -> R.string.gesture_swipe_right
        }

    private fun actionSummary(action: GestureAction): CharSequence =
        when (action) {
            is GestureAction.None -> activity.getString(R.string.action_none)
            is GestureAction.OpenSettings -> activity.getString(R.string.action_open_settings)
            is GestureAction.ShowAppSearch -> activity.getString(R.string.action_show_search)
            is GestureAction.OpenApp ->
                activity.getString(
                    R.string.action_open_app_named,
                    Favorites.label(activity, FavoriteApp(action.intent)),
                )
        }

    private fun chooseAction(gesture: Gesture) {
        val items =
            arrayOf(
                activity.getString(R.string.action_none),
                activity.getString(R.string.action_open_settings),
                activity.getString(R.string.action_open_app),
                activity.getString(R.string.action_show_search),
            )
        AlertDialog
            .Builder(activity)
            .setTitle(R.string.dialog_choose_action)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> applyGesture(gesture, GestureAction.None)
                    1 -> applyGesture(gesture, GestureAction.OpenSettings)
                    2 -> activity.openSearch { intent -> applyGesture(gesture, GestureAction.OpenApp(intent)) }
                    3 -> applyGesture(gesture, GestureAction.ShowAppSearch)
                }
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyGesture(
        gesture: Gesture,
        action: GestureAction,
    ) {
        val candidate = LauncherConfig.gestureActions(activity).toMutableMap()
        candidate[gesture] = action

        // Never allow a state with no way back into these settings.
        if (!LauncherConfig.hasSettingsAccess(candidate.values)) {
            AlertDialog
                .Builder(activity)
                .setTitle(R.string.error_keep_settings_title)
                .setMessage(R.string.error_keep_settings)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        LauncherConfig.saveGestureAction(activity, gesture, action)
        rebuildGestureRows()
    }

    companion object {
        const val KEY = "gestures"
    }
}
