package io.github.aurelia.nlauncher.ui.screens

import android.app.AlertDialog
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import io.github.aurelia.nlauncher.IconPacks
import io.github.aurelia.nlauncher.Icons
import io.github.aurelia.nlauncher.LauncherActivity
import io.github.aurelia.nlauncher.LauncherConfig
import io.github.aurelia.nlauncher.R
import io.github.aurelia.nlauncher.SettingFloat
import io.github.aurelia.nlauncher.SettingInt
import io.github.aurelia.nlauncher.ui.Screen
import java.util.Locale

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
        rows.addView(sizeRow(R.string.text_size, LauncherConfig.labelTextSp))
        rows.addView(
            settingRow(
                R.string.text_color,
                textColorSummary(),
                LauncherConfig.textColor.get(activity),
            ) { chooseTextColor() },
        )
        rows.addView(sizeRow(R.string.icon_size, LauncherConfig.iconSizeSp))
        rows.addView(sizeRow(R.string.favorites_gap, LauncherConfig.favoritesGapDp, valueRes = R.string.size_dp))
        rows.addView(sizeRow(R.string.search_gap, LauncherConfig.searchGapDp, valueRes = R.string.size_dp))
        rows.addView(settingRow(R.string.icon_pack, iconPackSummary()) { chooseIconPack() })
    }

    private fun textColorSummary(): String {
        val color = LauncherConfig.textColor.get(activity)
        return String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)
    }

    /** A size stored as a float, in sp. */
    private fun sizeRow(
        titleRes: Int,
        setting: SettingFloat,
    ): View =
        sliderRow(
            titleRes = titleRes,
            min = setting.min.toInt(),
            max = setting.max.toInt(),
            step = setting.step.toInt(),
            current = setting.get(activity),
            default = setting.defaultValue(activity),
        ) { setting.set(activity, it) }

    /** A spacing stored as an int, in dp. */
    private fun sizeRow(
        titleRes: Int,
        setting: SettingInt,
        valueRes: Int,
    ): View =
        sliderRow(
            titleRes = titleRes,
            min = setting.min,
            max = setting.max,
            step = setting.step,
            current = setting.get(activity).toFloat(),
            default = setting.defaultValue(activity).toFloat(),
            valueRes = valueRes,
        ) { setting.set(activity, it.toInt()) }

    /**
     * A size setting's row: its name and current value on the left, a slider on the
     * right that steps through [min]..[max] by [step], so every size the old radio
     * lists offered is still reachable. Changes are saved as the slider moves, and a
     * reset button appears once the value is no longer [default].
     */
    private fun sliderRow(
        titleRes: Int,
        min: Int,
        max: Int,
        step: Int,
        current: Float,
        default: Float,
        valueRes: Int = R.string.size_sp,
        save: (Float) -> Unit,
    ): View {
        val row = activity.layoutInflater.inflate(R.layout.item_size_setting, rows, false)
        row.findViewById<TextView>(R.id.size_name).setText(titleRes)
        val value = row.findViewById<TextView>(R.id.size_value)
        val reset = row.findViewById<ImageView>(R.id.size_reset)
        val stepCount = (max - min) / step
        val seekBar =
            row.findViewById<SeekBar>(R.id.size_slider).apply {
                contentDescription = activity.getString(titleRes)
                this.max = stepCount
                progress = ((current.toInt() - min) / step).coerceIn(0, stepCount)
            }

        fun showValue(progress: Int) {
            val amount = min + progress * step
            value.text = activity.getString(valueRes, amount)
            reset.visibility = if (amount.toFloat() == default) View.GONE else View.VISIBLE
        }

        showValue(seekBar.progress)
        seekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    showValue(progress)
                    save((min + progress * step).toFloat())
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            },
        )
        reset.setOnClickListener {
            seekBar.progress = ((default.toInt() - min) / step).coerceIn(0, stepCount)
        }
        return row
    }

    private fun chooseTextColor() {
        showColorPicker(activity, LauncherConfig.textColor.get(activity)) { color ->
            LauncherConfig.textColor.set(activity, color)
            rebuild()
        }
    }

    /** A row naming a setting, with its current value after it. */
    private fun settingRow(
        titleRes: Int,
        summary: String,
        valueColor: Int? = null,
        onClick: () -> Unit,
    ): View {
        val row = activity.layoutInflater.inflate(R.layout.item_gesture, rows, false)
        row.findViewById<TextView>(R.id.gesture_name).setText(titleRes)
        row.findViewById<TextView>(R.id.gesture_action).apply {
            text = summary
            if (valueColor != null) setTextColor(valueColor)
        }
        row.setOnClickListener { onClick() }
        return row
    }

    private fun iconPackSummary(): String =
        when {
            !LauncherConfig.iconsShown.get(activity) -> activity.getString(R.string.icon_pack_disabled)
            LauncherConfig.iconPack.get(activity) == null -> activity.getString(R.string.icon_pack_default)
            else -> {
                val packageName = LauncherConfig.iconPack.get(activity)
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
        LauncherConfig.iconsShown.set(activity, shown)
        LauncherConfig.iconPack.set(activity, pack)
        // Every icon on screen came from the old setting, so the cache has to go: what
        // is left behind would keep the launcher showing what was just replaced.
        Icons.clear()
        rebuild()
    }

    companion object {
        const val KEY = "look_and_feel"
    }
}
