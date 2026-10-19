package io.github.aurelia.nlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

object IconPacks {
    /**
     * How an icon pack announces itself. There is more than one spelling in the wild —
     * the framework's own [PackageManager] only ever defines one of them — so all of
     * them are asked and the results merged rather than betting on a single filter and
     * showing an empty list on a device that uses a different one.
     */
    private val PACK_FILTERS =
        listOf(
            Intent("org.adw.launcher.THEMES"),
            Intent("org.adw.launcher.icons.ACTION_PICK_ICON"),
            Intent("com.gau.go.launcherex.theme"),
            Intent("com.dlto.atom.launcher.THEME"),
            Intent("com.gridappsinc.launcher.theme.apk_action"),
            Intent("ch.deletescape.lawnchair.ICONPACK"),
            Intent("org.android.intent.action.ICON_PACKAGE").addCategory("org.android.intent.category.ICON_PACKAGE"),
            Intent("org.android.intent.action.ICON_PICKER").addCategory("org.android.intent.category.ICON_PICKER"),
            Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.ICON_PICKER"),
            Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME"),
        )

    class IconPack(
        val packageName: String,
        val label: String,
    )

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

    private fun load(
        context: Context,
        pack: String,
        intent: Intent,
    ): Drawable? {
        val pm = context.packageManager
        val component = intent.component ?: return null

        val packResources = pm.getResourcesForApplication(pack)

        val mappedName =
            findMappedIcon(
                context,
                packResources,
                pack,
                component,
            )

        if (mappedName != null) {
            val drawable =
                getPackDrawable(
                    packResources,
                    pack,
                    mappedName,
                )

            if (drawable != null) {
                return drawable
            }
        }

        // Fallback for packs that use the original icon resource name.
        val activity = pm.getActivityInfo(component, 0)
        val iconId = activity.iconResource

        if (iconId == 0) return null

        val activityResources =
            pm.getResourcesForApplication(activity.applicationInfo)

        val iconName =
            activityResources.getResourceEntryName(iconId)

        return getPackDrawable(packResources, pack, iconName)
    }

    /**
     * Finds the drawable name mapped to a component in appfilter.xml.
     */
    private fun findMappedIcon(
        context: Context,
        resources: Resources,
        pack: String,
        component: ComponentName,
    ): String? {
        fun matchesComponent(value: String?): Boolean {
            if (value == null) return false

            val normalized =
                value
                    .removePrefix("ComponentInfo{")
                    .removeSuffix("}")

            val mapped =
                ComponentName.unflattenFromString(normalized)
                    ?: return false

            return mapped.packageName == component.packageName &&
                mapped.className == component.className
        }

        fun parseFilter(parser: XmlPullParser): String? {
            var event = parser.eventType

            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG &&
                    parser.name == "item"
                ) {
                    val target =
                        parser.getAttributeValue(
                            null,
                            "component",
                        )

                    if (matchesComponent(target)) {
                        return parser.getAttributeValue(
                            null,
                            "drawable",
                        )
                    }
                }

                event = parser.next()
            }

            return null
        }

        // 1. Look in res/xml/appfilter.xml.
        val xmlId =
            resources.getIdentifier(
                "appfilter",
                "xml",
                pack,
            )

        if (xmlId != 0) {
            try {
                resources.getXml(xmlId).use { parser ->
                    parseFilter(parser)?.let { return it }
                }
            } catch (_: Exception) {
                // Try assets.
            }
        }

        // 2. Look in assets/appfilter.xml.
        try {
            val packContext =
                context.createPackageContext(
                    pack,
                    Context.CONTEXT_IGNORE_SECURITY,
                )

            packContext.assets.open("appfilter.xml").use { stream ->
                val parser = android.util.Xml.newPullParser()
                parser.setInput(stream, null)
                return parseFilter(parser)
            }
        } catch (_: Exception) {
            // No accessible mapping.
        }

        return null
    }

    /**
     * Resolves a drawable name in drawable/ or mipmap/.
     */
    private fun getPackDrawable(
        resources: Resources,
        pack: String,
        name: String,
    ): Drawable? {
        val drawableId =
            resources.getIdentifier(
                name,
                "drawable",
                pack,
            )

        val mipmapId =
            if (drawableId == 0) {
                resources.getIdentifier(name, "mipmap", pack)
            } else {
                0
            }

        val id = if (drawableId != 0) drawableId else mipmapId

        return if (id != 0) {
            resources.getDrawable(id, null)
        } else {
            null
        }
    }
}
