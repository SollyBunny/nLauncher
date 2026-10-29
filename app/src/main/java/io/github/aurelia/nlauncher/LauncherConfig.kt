package io.github.aurelia.nlauncher

import android.content.Context
import android.content.Intent
import android.provider.Settings

/** A press or swipe the user can bind an action to. */
enum class Gesture(
    val key: String,
) {
    HOLD("hold"),
    SWIPE_UP("swipe_up"),
    SWIPE_DOWN("swipe_down"),
    SWIPE_LEFT("swipe_left"),
    SWIPE_RIGHT("swipe_right"),
}

/** What a [Gesture] does when it fires. */
sealed class GestureAction {
    object None : GestureAction()

    object OpenSettings : GestureAction()

    object ShowAppSearch : GestureAction()

    class OpenApp(
        val intent: Intent,
    ) : GestureAction()

    fun serialize(): String =
        when (this) {
            is None -> "none"
            is OpenSettings -> "settings"
            is ShowAppSearch -> "app_search"
            is OpenApp -> "app:" + intent.toUri(Intent.URI_INTENT_SCHEME)
        }

    companion object {
        fun parse(value: String?): GestureAction =
            when {
                value == null -> None
                value == "settings" -> OpenSettings
                value == "app_search" -> ShowAppSearch
                value.startsWith("app:") ->
                    runCatching {
                        OpenApp(Intent.parseUri(value.removePrefix("app:"), Intent.URI_INTENT_SCHEME))
                    }.getOrElse { None }
                else -> None
            }
    }
}

/**
 * Persisted launcher configuration. Favorites keep their order; gestures default
 * to "hold press opens the settings" so there is always a way back in.
 */
object LauncherConfig {
    private const val PREFS = "nlauncher"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_WIDGETS = "widgets"
    private const val KEY_ICON_PACK = "icon_pack"
    private const val KEY_ICONS_SHOWN = "icons_shown"
    private const val KEY_LABEL_TEXT_SP = "label_text_sp"
    private const val KEY_TEXT_COLOR = "text_color"
    private const val KEY_ICON_SIZE_SP = "icon_size_sp"

    /** The size app labels start at, in sp, before the user picks another. */
    const val DEFAULT_LABEL_TEXT_SP = 18f

    /** The size app icons start at, in sp, before the user picks another. */
    const val DEFAULT_ICON_SIZE_SP = 40f

    // Parsing a stored favorite means an Intent.parseUri per entry, and the launcher
    // asks for them on every Home press, so the parsed list is kept against the exact
    // text it came from.
    private var parsedRaw: String? = null
    private var parsedFavorites: List<FavoriteApp> = emptyList()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun defaultFavorites() = listOf(FavoriteApp(Intent(Settings.ACTION_SETTINGS)))

    private fun defaultWidgets() = listOf(Widget.CLOCK, Widget.DATE)

    /**
     * The stored favorites, in order. An absent preference means "never configured",
     * so the default (system Settings) is used; a preference that is present but
     * empty means the user deliberately removed every favorite.
     */
    fun favorites(context: Context): List<FavoriteApp> {
        val raw = prefs(context).getString(KEY_FAVORITES, null) ?: return defaultFavorites()
        if (raw == parsedRaw) return parsedFavorites
        parsedFavorites =
            raw
                .split('\n')
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    runCatching { FavoriteApp(Intent.parseUri(line, Intent.URI_INTENT_SCHEME)) }
                        .getOrNull()
                }
        parsedRaw = raw
        return parsedFavorites
    }

    fun saveFavorites(
        context: Context,
        favorites: List<FavoriteApp>,
    ) {
        val raw = favorites.joinToString("\n") { it.intent.toUri(Intent.URI_INTENT_SCHEME) }
        prefs(context).edit().putString(KEY_FAVORITES, raw).apply()
    }

    /**
     * The stored widgets, in order. An absent preference means "never configured",
     * so the default (clock, then date) is used; a preference that is present but
     * empty means the user deliberately removed every widget. Keys written by a
     * newer version are dropped rather than crashing this one.
     */
    fun widgets(context: Context): List<Widget> {
        val raw = prefs(context).getString(KEY_WIDGETS, null) ?: return defaultWidgets()
        return raw.split('\n').filter { it.isNotBlank() }.mapNotNull { Widget.fromKey(it) }
    }

    fun saveWidgets(
        context: Context,
        widgets: List<Widget>,
    ) {
        val raw = widgets.joinToString("\n") { it.key }
        prefs(context).edit().putString(KEY_WIDGETS, raw).apply()
    }

    /**
     * The chosen icon pack's package, or null for the system icons.
     *
     * The package is stored rather than anything about the pack itself, so a pack that
     * is uninstalled simply stops being found and the launcher falls back to the system
     * icons without anything having to be cleaned up.
     */
    fun iconPack(context: Context): String? = prefs(context).getString(KEY_ICON_PACK, null)?.takeIf { it.isNotBlank() }

    fun saveIconPack(
        context: Context,
        pack: String?,
    ) {
        prefs(context).edit().putString(KEY_ICON_PACK, pack).apply()
    }

    /** Whether app icons are shown at all: all of them, one pack's, or none. */
    fun iconsShown(context: Context): Boolean = prefs(context).getBoolean(KEY_ICONS_SHOWN, true)

    fun saveIconsShown(
        context: Context,
        shown: Boolean,
    ) {
        prefs(context).edit().putBoolean(KEY_ICONS_SHOWN, shown).apply()
    }

    /** How large app labels are, in sp. Widgets scale with the same setting. */
    fun labelTextSp(context: Context): Float = prefs(context).getFloat(KEY_LABEL_TEXT_SP, DEFAULT_LABEL_TEXT_SP)

    fun saveLabelTextSp(
        context: Context,
        sp: Float,
    ) {
        prefs(context).edit().putFloat(KEY_LABEL_TEXT_SP, sp).apply()
    }

    /**
     * The color app labels and widget text are drawn in, or the theme's primary text
     * color when the user has not picked one.
     */
    fun textColor(context: Context): Int = prefs(context).getInt(KEY_TEXT_COLOR, context.getColor(R.color.text_primary))

    fun saveTextColor(
        context: Context,
        color: Int,
    ) {
        prefs(context).edit().putInt(KEY_TEXT_COLOR, color).apply()
    }

    /** How large app icons are, in sp. Unlike labels, icons do not scale on their own. */
    fun iconSizeSp(context: Context): Float = prefs(context).getFloat(KEY_ICON_SIZE_SP, DEFAULT_ICON_SIZE_SP)

    fun saveIconSizeSp(
        context: Context,
        sp: Float,
    ) {
        prefs(context).edit().putFloat(KEY_ICON_SIZE_SP, sp).apply()
    }

    fun gestureActions(context: Context): Map<Gesture, GestureAction> = Gesture.entries.associateWith { gestureAction(context, it) }

    fun gestureAction(
        context: Context,
        gesture: Gesture,
    ): GestureAction {
        val stored = prefs(context).getString("gesture_" + gesture.key, null)
        if (stored != null) return GestureAction.parse(stored)
        return when (gesture) {
            // Always keep a way back into these settings, and make the app search
            // reachable out of the box.
            Gesture.HOLD -> GestureAction.OpenSettings
            Gesture.SWIPE_UP -> GestureAction.ShowAppSearch
            else -> GestureAction.None
        }
    }

    fun saveGestureAction(
        context: Context,
        gesture: Gesture,
        action: GestureAction,
    ) {
        prefs(context).edit().putString("gesture_" + gesture.key, action.serialize()).apply()
    }

    /** The settings screen must stay reachable through at least one gesture. */
    fun hasSettingsAccess(actions: Collection<GestureAction>): Boolean = actions.any { it is GestureAction.OpenSettings }
}
