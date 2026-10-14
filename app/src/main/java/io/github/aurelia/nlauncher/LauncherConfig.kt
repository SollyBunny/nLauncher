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
                // "search_bar" was the old key for this action; still read it so stored
                // bindings survive the rename.
                value == "app_search" || value == "search_bar" -> ShowAppSearch
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

    // Parsing a stored favorite means an Intent.parseUri per entry, and the launcher
    // asks for them on every Home press, so the parsed list is kept against the exact
    // text it came from.
    private var parsedRaw: String? = null
    private var parsedFavorites: List<FavoriteApp> = emptyList()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun defaultFavorites() = listOf(FavoriteApp(Intent(Settings.ACTION_SETTINGS)))

    /**
     * The stored favorites, in order. An absent preference means "never configured",
     * so the default (system Settings) is used; a preference that is present but
     * empty means the user deliberately removed every favorite.
     */
    fun favorites(context: Context): List<FavoriteApp> {
        val raw = prefs(context).getString(KEY_FAVORITES, null) ?: return defaultFavorites()
        if (raw == parsedRaw) return parsedFavorites
        parsedFavorites =
            raw.split('\n')
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
