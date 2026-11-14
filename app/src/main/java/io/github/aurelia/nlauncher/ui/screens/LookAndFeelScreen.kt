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
        rows.addView(
            sizeRow(
                R.string.text_size,
                TEXT_SIZE_MIN,
                TEXT_SIZE_MAX,
                TEXT_SIZE_STEP,
                LauncherConfig.labelTextSp(activity),
                LauncherConfig.DEFAULT_LABEL_TEXT_SP,
            ) { LauncherConfig.saveLabelTextSp(activity, it) },
        )
        rows.addView(
            settingRow(
                R.string.text_color,
                textColorSummary(),
                LauncherConfig.textColor(activity),
            ) { chooseTextColor() },
        )
        rows.addView(
            sizeRow(
                R.string.icon_size,
                ICON_SIZE_MIN,
                ICON_SIZE_MAX,
                ICON_SIZE_STEP,
                LauncherConfig.iconSizeSp(activity),
                LauncherConfig.DEFAULT_ICON_SIZE_SP,
            ) { LauncherConfig.saveIconSizeSp(activity, it) },
        )
        rows.addView(
            sizeRow(
                R.string.favorites_gap,
                GAP_MIN,
                GAP_MAX,
                GAP_STEP,
                LauncherConfig.favoritesGapDp(activity).toFloat(),
                LauncherConfig.DEFAULT_FAVORITES_GAP_DP.toFloat(),
                valueRes = R.string.size_dp,
            ) { LauncherConfig.saveFavoritesGapDp(activity, it.toInt()) },
        )
        rows.addView(
            sizeRow(
                R.string.search_gap,
                GAP_MIN,
                GAP_MAX,
                GAP_STEP,
                LauncherConfig.searchGapDp(activity).toFloat(),
                LauncherConfig.DEFAULT_SEARCH_GAP_DP.toFloat(),
                valueRes = R.string.size_dp,
            ) { LauncherConfig.saveSearchGapDp(activity, it.toInt()) },
        )
        rows.addView(settingRow(R.string.icon_pack, iconPackSummary()) { chooseIconPack() })
    }

    private fun textColorSummary(): String {
        val color = LauncherConfig.textColor(activity)
        return String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)
    }

    /**
     * A size setting: its name and current value on the left, a slider on the right
     * that steps through [min]..[max] by [step], so every size the old radio lists
     * offered is still reachable. Changes are saved as the slider moves, and a reset
     * button appears once the value is no longer [default].
     */
    private fun sizeRow(
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
        showColorPicker(activity, LauncherConfig.textColor(activity)) { color ->
            LauncherConfig.saveTextColor(activity, color)
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

        /** The range the text size slider covers, in sp. */
        private const val TEXT_SIZE_MIN = 12
        private const val TEXT_SIZE_MAX = 32
        private const val TEXT_SIZE_STEP = 2

        /** The range the icon size slider covers, in sp. The default is among them. */
        private const val ICON_SIZE_MIN = 24
        private const val ICON_SIZE_MAX = 96
        private const val ICON_SIZE_STEP = 8

        /** The range the gap sliders cover, in dp. */
        private const val GAP_MIN = 2
        private const val GAP_MAX = 16
        private const val GAP_STEP = 1
    }
}
