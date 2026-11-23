package io.github.aurelia.nlauncher.ui.screens

import android.app.AlertDialog
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
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
import io.github.aurelia.nlauncher.ui.colorToHex

class LookAndFeelScreen(
    private val activity: LauncherActivity,
) : Screen {
    override val key = KEY

    override val view: View = activity.layoutInflater.inflate(R.layout.screen_settings, null, false)

    private val rows = settingsList(activity)

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
        rows.addView(textColorRow())
        rows.addView(sizeRow(R.string.icon_size, LauncherConfig.iconSizeSp))
        rows.addView(sizeRow(R.string.favorites_gap, LauncherConfig.favoritesGapDp, valueRes = R.string.size_dp))
        rows.addView(sizeRow(R.string.search_gap, LauncherConfig.searchGapDp, valueRes = R.string.size_dp))
        rows.addView(
            toggleRow(R.string.align_to_bottom, LauncherConfig.alignToBottom.get(activity)) { enabled ->
                LauncherConfig.alignToBottom.set(activity, enabled)
            },
        )
        rows.addView(settingRow(activity, rows, R.string.icon_pack, iconPackSummary()) { chooseIconPack() })
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

    /** A boolean setting's row: the whole row flips the checkbox, which is saved on change. */
    private fun toggleRow(
        titleRes: Int,
        checked: Boolean,
        onChanged: (Boolean) -> Unit,
    ): View {
        val row = activity.layoutInflater.inflate(R.layout.item_toggle_setting, rows, false)
        row.findViewById<TextView>(R.id.toggle_name).setText(titleRes)
        val toggle = row.findViewById<CheckBox>(R.id.toggle_value)
        toggle.isChecked = checked
        row.setOnClickListener {
            val enabled = !toggle.isChecked
            toggle.isChecked = enabled
            onChanged(enabled)
        }
        return row
    }

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

    /**
     * The row shows "adaptive" or the custom hex, with a reset button only once a
     * custom color exists. Tapping the row opens the picker, which saves on OK alone.
     */
    private fun textColorRow(): View {
        val row = activity.layoutInflater.inflate(R.layout.item_text_color_setting, rows, false)
        val customColor = LauncherConfig.textColor.get(activity)
        val value = row.findViewById<TextView>(R.id.text_color_value)
        val reset = row.findViewById<ImageView>(R.id.text_color_reset)

        if (customColor == null) {
            value.setText(R.string.text_color_adaptive)
            value.setTextColor(activity.getColor(R.color.text_secondary))
        } else {
            value.text = colorToHex(customColor)
            value.setTextColor(customColor)
        }
        reset.visibility = if (customColor == null) View.GONE else View.VISIBLE

        reset.setOnClickListener {
            LauncherConfig.textColor.set(activity, null)
            rebuild()
        }
        row.setOnClickListener { chooseTextColor() }
        return row
    }

    private fun chooseTextColor() {
        val initial = LauncherConfig.textColor.get(activity) ?: LauncherConfig.resolvedTextColor(activity)
        showColorPicker(activity, initial) { color ->
            LauncherConfig.textColor.set(activity, color)
            rebuild()
        }
    }

    private fun iconPackSummary(): String {
        if (!LauncherConfig.iconsShown.get(activity)) return activity.getString(R.string.icon_pack_disabled)
        return when (val packageName = LauncherConfig.iconPack.get(activity)) {
            null -> activity.getString(R.string.icon_pack_default)
            else -> {
                val label =
                    IconPacks
                        .installed(activity)
                        .firstOrNull { it.packageName == packageName }
                        ?.label ?: packageName
                activity.getString(R.string.icon_pack_custom_set, label)
            }
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
