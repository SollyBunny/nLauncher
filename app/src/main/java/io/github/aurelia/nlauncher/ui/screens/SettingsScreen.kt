package io.github.aurelia.nlauncher.ui.screens

import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.aurelia.nlauncher.LauncherActivity
import io.github.aurelia.nlauncher.R
import io.github.aurelia.nlauncher.ui.Screen
import io.github.aurelia.nlauncher.ui.dpToPx

class SettingsScreen(
    private val activity: LauncherActivity,
) : Screen {
    override val key = KEY

    override val view: View = activity.layoutInflater.inflate(R.layout.screen_settings, null, false)

    private val launcherStatus =
        TextView(activity).apply {
            gravity = Gravity.CENTER
            setTextColor(activity.getColor(R.color.text_secondary))
            setTextSize(13f)
            setPaddingRelative(24f.dpToPx(activity), 8f.dpToPx(activity), 24f.dpToPx(activity), 16f.dpToPx(activity))
            val ripple = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { requestDefaultLauncher() }
        }

    init {
        // The menu sits directly on the home screen, so there is nothing to go back to.
        setHeader(activity, view, R.string.app_name, showBack = false)

        val list = settingsList(activity)
        list.addView(menuRow(activity, list, R.string.menu_favorites) { activity.pushEditFavorites() })
        list.addView(menuRow(activity, list, R.string.menu_widgets) { activity.pushEditWidgets() })
        list.addView(menuRow(activity, list, R.string.menu_gestures) { activity.pushGestures() })
        list.addView(menuRow(activity, list, R.string.menu_look_and_feel) { activity.pushLookAndFeel() })
        val content: FrameLayout = view.findViewById(R.id.settings_content)
        content.addView(scrollableRows(activity, list))

        view.findViewById<LinearLayout>(R.id.settings_root).addView(launcherStatus)
        updateLauncherStatus()
    }

    override fun onActivated() {
        // The default launcher may have changed while the system's picker was open.
        updateLauncherStatus()
    }

    private fun updateLauncherStatus() {
        launcherStatus.text =
            activity.getString(
                if (isDefaultLauncher()) {
                    R.string.launcher_status_default
                } else {
                    R.string.launcher_status_not_default
                },
                activity.getString(R.string.app_name),
            )
    }

    private fun isDefaultLauncher(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
        } else {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            activity.packageManager
                .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName == activity.packageName
        }

    private fun requestDefaultLauncher() {
        val roleRequest =
            if (!isDefaultLauncher() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activity
                    .getSystemService(RoleManager::class.java)
                    ?.takeIf { it.isRoleAvailable(RoleManager.ROLE_HOME) }
                    ?.createRequestRoleIntent(RoleManager.ROLE_HOME)
            } else {
                null
            }
        activity.launch(roleRequest ?: Intent(Settings.ACTION_HOME_SETTINGS))
    }

    companion object {
        const val KEY = "settings"
    }
}
