package io.github.aurelia.nlauncher.ui.screens

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.aurelia.nlauncher.LauncherActivity
import io.github.aurelia.nlauncher.R
import io.github.aurelia.nlauncher.ui.Screen
import io.github.aurelia.nlauncher.ui.padForSystemBars

/**
 * The settings menu, a screen on the launcher's stack rather than an activity of its
 * own. It is opaque (it paints its own background over the wallpaper) and leads to the
 * two other settings screens: the favorites editor and the gesture bindings.
 *
 * Shares [R.layout.screen_settings] with [GesturesScreen]: same header, different
 * content panel and title.
 */
class SettingsScreen(
    private val activity: LauncherActivity,
) : Screen {
    override val key = KEY

    override val view: View = activity.layoutInflater.inflate(R.layout.screen_settings, null, false)

    init {
        // The menu sits directly on the home screen, so there is nothing to go back to.
        setHeader(activity, view, R.string.app_name, showBack = false)

        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        list.addView(menuRow(R.string.menu_favorites) { activity.pushEditFavorites() })
        list.addView(menuRow(R.string.menu_widgets) { activity.pushEditWidgets() })
        list.addView(menuRow(R.string.menu_gestures) { activity.pushGestures() })
        val content: FrameLayout = view.findViewById(R.id.settings_content)
        content.addView(scrollableRows(activity, list))
    }

    private fun menuRow(
        titleRes: Int,
        onClick: () -> Unit,
    ): View {
        val row = activity.layoutInflater.inflate(R.layout.item_menu, null, false)
        row.findViewById<TextView>(R.id.menu_title).setText(titleRes)
        row.setOnClickListener { onClick() }
        return row
    }

    companion object {
        const val KEY = "settings"
    }
}

/** A panel of rows that scrolls once it outgrows the screen. */
internal fun scrollableRows(
    activity: Activity,
    inner: View,
): View =
    ScrollView(activity).apply {
        isFillViewport = true
        addView(
            inner,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

/**
 * The header every screen sharing [R.layout.screen_settings] wears: its title, and
 * the way out of it.
 *
 * The two 40dp slots on the left hold one icon at a time — the launcher icon on the
 * screen you enter the settings at, the back arrow on every screen below it, which is
 * what a submenu of the settings should offer. They are the same size and padding, so
 * the title does not move between them.
 */
internal fun setHeader(
    activity: LauncherActivity,
    view: View,
    titleRes: Int,
    showBack: Boolean,
) {
    view.findViewById<View>(R.id.settings_back).apply {
        visibility = if (showBack) View.VISIBLE else View.GONE
        if (showBack) setOnClickListener { activity.back() }
    }
    view.findViewById<View>(R.id.settings_app_icon).visibility =
        if (showBack) View.GONE else View.VISIBLE
    view.findViewById<TextView>(R.id.settings_title).setText(titleRes)
    view.padForSystemBars()
}
