package io.github.aurelia.nlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import java.util.Locale

/**
 * Icon packs, and loading an app's icon through one.
 *
 * There is no standard API for this. An icon pack is an ordinary app that ships a
 * `drawable` resource named after each activity whose icon it replaces — the same name
 * the app itself uses, e.g. `ic_launcher` — and advertises itself through an intent
 * filter. So applying a pack is: work out which resource the target app names its icon
 * with, ask the pack's own resources for a drawable of that name, and fall back to the
 * system icon if it does not have one.
 *
 * Every lookup is off the main thread and behind [Icons]'s cache, like every other icon
 * load, because [Resources.getIdentifier] walks the pack's whole resource table.
 */
object IconPacks {
    /**
     * How an icon pack announces itself. There is more than one spelling in the wild —
     * the framework's own [PackageManager] only ever defines one of them — so all of
     * them are asked and the results merged rather than betting on a single filter and
     * showing an empty list on a device that uses a different one.
     */
    private val PACK_FILTERS =
        listOf(
            Intent("org.android.intent.action.ICON_PACKAGE").addCategory("org.android.intent.category.ICON_PACKAGE"),
            Intent("org.android.intent.action.ICON_PICKER").addCategory("org.android.intent.category.ICON_PICKER"),
            Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.ICON_PICKER"),
        )

    /** One installed icon pack: what to show in the picker, and what to load through. */
    class IconPack(
        val packageName: String,
        val label: String,
    )

    /**
     * Every icon pack installed, by label. The system icons are not in here: they are
     * not an app, and the picker always offers them itself, before anything in this list.
     */
    fun installed(context: Context): List<IconPack> {
        val pm = context.packageManager
        val found = mutableMapOf<String, IconPack>()
        for (filter in PACK_FILTERS) {
            val matches =
                runCatching { pm.queryIntentActivities(filter, PackageManager.MATCH_DEFAULT_ONLY) }
                    .getOrDefault(emptyList())
            for (info in matches) {
                val name = info.activityInfo.packageName
                if (name == context.packageName) continue
                if (name in found) continue
                found[name] = IconPack(name, info.loadLabel(pm).toString())
            }
        }
        return found.values.sortedBy { it.label.lowercase(Locale.ROOT) }
    }

    /**
     * The icon [component]'s app would have, but through the chosen pack.
     *
     * Returns null whenever the pack cannot supply one — no pack chosen, the pack not
     * installed any more, no resource of that name in it, or a pack whose lookup throws
     * — which is the caller's signal to fall back to the system's own icon.
     */
    fun icon(
        context: Context,
        intent: Intent,
    ): Drawable? {
        val pack = LauncherConfig.iconPack(context) ?: return null
        return runCatching { load(context, pack, intent) }.getOrNull()
    }

    /**
     * The pack's drawable named after [component]'s icon resource.
     */
    private fun load(
        context: Context,
        pack: String,
        intent: Intent,
    ): Drawable? {
        val pm = context.packageManager
        val component = intent.component ?: return null

        return try {
            val activity = pm.getActivityInfo(component, 0)
            val iconId = activity.iconResource
            if (iconId == 0) return null

            val activityResources = pm.getResourcesForApplication(activity.applicationInfo)
            val iconName = activityResources.getResourceEntryName(iconId)

            val packResources = pm.getResourcesForApplication(pack)
            val id = packResources.getIdentifier(iconName, "drawable", pack)
            if (id == 0) return null

            packResources.getDrawable(id, null)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: Resources.NotFoundException) {
            null
        }
    }
}
