package io.github.aurelia.nlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings

/**
 * The launcher activity of the app that would handle [intent], or null when no app
 * can be launched for it.
 *
 * The activity that handles an intent is often not the one shown in the app drawer
 * (Settings opens its homepage, a document picker opens its picker), and icon packs
 * are indexed by the drawer's component. So this looks through the handlers until it
 * finds a package with a launcher activity, and prefers the intent's explicit
 * component when it has one.
 */
fun launchComponent(
    context: Context,
    intent: Intent,
): ComponentName? {
    intent.component?.let { return it }

    val pm = context.packageManager
    val packages =
        buildList {
            pm
                .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName
                ?.let(::add)
            pm
                .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapTo(this) { it.activityInfo.packageName }
        }.distinct()
            .filter { it != context.packageName }

    for (packageName in packages) {
        // The handler itself may not be launchable (the chooser, a document picker),
        // so use the package's launcher entry rather than the handler's activity.
        pm.getLaunchIntentForPackage(packageName)?.component?.let { return it }
    }
    return null
}

/**
 * Resolve [intent] to the app that would open it, so that favorites are app icons
 * that launch the app itself instead of whatever screen the intent names.
 *
 * Falls back to [intent] when no launchable app handles it, so the favorite is still
 * shown even if all it can do is report that nothing can open it.
 */
fun appifyIntent(
    context: Context,
    intent: Intent,
): Intent {
    val component = launchComponent(context, intent) ?: return intent
    return Intent().setComponent(component)
}

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

object LauncherConfig {
    val favorites: SettingList<FavoriteApp> =
        SettingList(
            key = "favorites",
            default = ::defaultFavorites,
            encode = { it.intent.toUri(Intent.URI_INTENT_SCHEME) },
            decode = { line ->
                runCatching { FavoriteApp(Intent.parseUri(line, Intent.URI_INTENT_SCHEME)) }
                    .getOrNull()
            },
        )

    val widgets: SettingList<Widget> =
        SettingList(
            key = "widgets",
            default = { listOf(Widget.CLOCK, Widget.DATE) },
            encode = { it.key },
            decode = Widget::fromKey,
        )

    val iconPack = SettingString("icon_pack")

    /** Whether app icons are shown at all: all of them, one pack's, or none. */
    val iconsShown = SettingBool("icons_shown", default = true)

    /** How large app labels are, in sp. Widgets scale with the same setting. */
    val labelTextSp =
        SettingFloat(
            key = "label_text_sp",
            default = 18f,
            min = 12f,
            max = 32f,
            step = 2f,
        )

    /**
     * The color app labels and widget text are drawn in, or the theme's primary text
     * color when the user has not picked one.
     */
    val textColor = SettingColor("text_color") { it.getColor(R.color.text_primary) }

    /** How large app icons are, in sp. Unlike labels, icons do not scale on their own. */
    val iconSizeSp =
        SettingFloat(
            key = "icon_size_sp",
            default = 40f,
            min = 24f,
            max = 96f,
            step = 8f,
        )

    /** The space between favorite apps on the home screen, in dp. */
    val favoritesGapDp = SettingInt("favorites_gap_dp", default = 12, min = 2, max = 16, step = 1)

    /** The space between the apps listed in the search menu, in dp. */
    val searchGapDp = SettingInt("search_gap_dp", default = 8, min = 2, max = 16, step = 1)

    /** Every gesture's own setting, keyed by the gesture it belongs to. */
    val gestureSettings: Map<Gesture, SettingGestureAction> =
        Gesture.entries.associateWith { gesture ->
            SettingGestureAction(
                gesture = gesture,
                default =
                    when (gesture) {
                        // Always keep a way back into these settings, and make the app
                        // search reachable out of the box.
                        Gesture.HOLD -> GestureAction.OpenSettings
                        Gesture.SWIPE_UP -> GestureAction.ShowAppSearch
                        else -> GestureAction.None
                    },
            )
        }

    fun gestureAction(
        context: Context,
        gesture: Gesture,
    ): GestureAction = gestureSettings.getValue(gesture).get(context)

    fun gestureActions(context: Context): Map<Gesture, GestureAction> = gestureSettings.mapValues { it.value.get(context) }

    fun saveGestureAction(
        context: Context,
        gesture: Gesture,
        action: GestureAction,
    ) {
        gestureSettings.getValue(gesture).set(context, action)
    }

    /** The settings screen must stay reachable through at least one gesture. */
    fun hasSettingsAccess(actions: Collection<GestureAction>): Boolean = actions.any { it is GestureAction.OpenSettings }

    /** The favorites a fresh install starts with, resolved to the apps that handle them. */
    private fun defaultFavorites(context: Context) =
        listOf(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            },
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            Intent(Intent.ACTION_VIEW, Uri.parse("mailto:")),
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_MESSAGING)
            },
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    "image/*",
                )
            },
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_CALENDAR)
            },
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent(Settings.ACTION_SETTINGS),
        ).map { appifyIntent(context, it) }.distinctBy { it }.map { FavoriteApp(it) }
}
