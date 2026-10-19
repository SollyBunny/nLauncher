package io.github.aurelia.nlauncher.ui.screens

import android.app.AlertDialog
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.aurelia.nlauncher.IconPacks
import io.github.aurelia.nlauncher.Icons
import io.github.aurelia.nlauncher.LauncherActivity
import io.github.aurelia.nlauncher.LauncherConfig
import io.github.aurelia.nlauncher.R
import io.github.aurelia.nlauncher.ui.Screen

/**
 * "Look and feel": the settings that change what the launcher looks like rather than
 * what it does. Each row shows the current setting as well as its name, and opens the
 * dialog that changes it.
 *
 * A submenu like the favorites and widgets editors, so it shares their header and gets
 * the back arrow rather than the launcher icon.
 */
class LookAndFeelScreen(
    private val activity: LauncherActivity,
) : Screen {
    override val key = KEY

    override val view: View = activity.layoutInflater.inflate(R.layout.screen_settings, null, false)

    private val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    init {
        setHeader(activity, view, R.string.menu_look_and_feel, showBack = true)

        val content: FrameLayout = view.findViewById(R.id.settings_content)
        content.addView(scrollableRows(activity, rows))
    }

    override fun onActivated() {
        rebuild()
    }

    private fun rebuild() {
        rows.removeAllViews()
        rows.addView(settingRow(R.string.icon_pack, iconPackSummary()) { chooseIconPack() })
    }

    /** A row naming a setting, with its current value after it. */
    private fun settingRow(
        titleRes: Int,
        summary: String,
        onClick: () -> Unit,
    ): View {
        val row = activity.layoutInflater.inflate(R.layout.item_gesture, rows, false)
        row.findViewById<TextView>(R.id.gesture_name).setText(titleRes)
        row.findViewById<TextView>(R.id.gesture_action).text = summary
        row.setOnClickListener { onClick() }
        return row
    }

    private fun iconPackSummary(): String =
        when {
            !LauncherConfig.iconsShown(activity) -> activity.getString(R.string.icon_pack_disabled)
            LauncherConfig.iconPack(activity) == null -> activity.getString(R.string.icon_pack_default)
            else -> {
                val packageName = LauncherConfig.iconPack(activity)
                val label =
                    IconPacks
                        .installed(activity)
                        .firstOrNull { it.packageName == packageName }
                        ?.label ?: packageName
                activity.getString(R.string.icon_pack_custom_set, label)
            }
        }

    /**
     * Default (the icons the apps ship with), Custom (an icon pack, picked from the
     * ones installed) or Disabled (no icons at all).
     *
     * Picking drops the icon cache, so every screen showing icons draws them again the
     * next time it is activated — for the home screen, the back press that leaves here.
     */
    private fun chooseIconPack() {
        val options =
            arrayOf(
                activity.getString(R.string.icon_pack_default),
                activity.getString(R.string.icon_pack_custom),
                activity.getString(R.string.icon_pack_disabled),
            )
        AlertDialog
            .Builder(activity)
            .setTitle(R.string.icon_pack)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> setIconPack(null, shown = true)
                    1 -> chooseInstalledPack()
                    else -> setIconPack(null, shown = false)
                }
            }.show()
    }

    /** The installed icon packs, so "Custom" has something to choose from. */
    private fun chooseInstalledPack() {
        val packs = IconPacks.installed(activity)
        if (packs.isEmpty()) {
            AlertDialog
                .Builder(activity)
                .setTitle(R.string.icon_pack_custom)
                .setMessage(R.string.icon_pack_none)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        AlertDialog
            .Builder(activity)
            .setTitle(R.string.icon_pack_custom)
            .setItems(packs.map { it.label }.toTypedArray()) { _, which ->
                setIconPack(packs[which].packageName, shown = true)
            }.show()
    }

    private fun setIconPack(
        pack: String?,
        shown: Boolean,
    ) {
        LauncherConfig.saveIconsShown(activity, shown)
        LauncherConfig.saveIconPack(activity, pack)
        // Every icon on screen came from the old setting, so the cache has to go: what
        // is left behind would keep the launcher showing what was just replaced.
        Icons.clear()
        rebuild()
    }

    companion object {
        const val KEY = "look_and_feel"
    }
}
