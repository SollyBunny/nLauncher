package io.github.aurelia.nlauncher

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Xml
import android.widget.Toast
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

    class IconPackQuery {
        val pack: String
        private val resources: Resources?
        private val drawableNames: HashMap<ComponentName, String>
        private val resolvedIcons: HashMap<ComponentName, Int> = HashMap()

        constructor(context: Context, pack: String) {
            this.pack = pack
            val appContext = context.applicationContext

            var resources =
                try {
                    appContext.packageManager.getResourcesForApplication(pack)
                } catch (e: PackageManager.NameNotFoundException) {
                    Toast.makeText(context, context.getString(R.string.icon_pack_not_found, pack), Toast.LENGTH_SHORT).show()
                    null
                }

            this.drawableNames = try {
                resources?.let { loadIcons(appContext, resources) }
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.icon_pack_load_error, pack, e.message), Toast.LENGTH_SHORT).show()
                resources = null
                null
            } ?: HashMap()

            this.resources = resources
        }

        @SuppressLint("DiscouragedApi")
        private fun loadIcons(
            context: Context,
            resources: Resources,
        ): HashMap<ComponentName, String> {
            // Time sensitive
            fun parse(parser: XmlPullParser): HashMap<ComponentName, String> {
                val result = HashMap<ComponentName, String>(2048) // Prealloc
                while (true) {
                    when (parser.next()) {
                        XmlPullParser.START_TAG -> {
                            if (parser.name != "item") continue
                            val componentValue = parser.getAttributeValue(null, "component")
                            val drawableName = parser.getAttributeValue(null, "drawable")

                            // Do 0 or 1 substrings instead of 2 with removePrefix/Suffix
                            val value = componentValue ?: continue
                            val start =
                                if (value.startsWith("ComponentInfo{")) {
                                    "ComponentInfo{".length
                                } else {
                                    0
                                }
                            val end =
                                if (value.endsWith("}")) {
                                    value.length - 1
                                } else {
                                    value.length
                                }
                            val normalized =
                                if (start == 0 && end == value.length) {
                                    value
                                } else {
                                    value.substring(start, end)
                                }

                            val component = ComponentName.unflattenFromString(normalized)
                            result.putIfAbsent(component ?: continue, drawableName ?: continue)
                        }
                        XmlPullParser.END_DOCUMENT -> return result
                    }
                }

                return result
            }

            // 1. Try res/xml/appfilter.xml.
            @SuppressLint("DiscouragedAPI")
            val xmlId =
                resources.getIdentifier(
                    "appfilter",
                    "xml",
                    pack,
                )

            if (xmlId != 0) {
                return resources.getXml(xmlId).use { parser ->
                    parse(parser)
                }
            }

            // 2. Fall back to assets/appfilter.xml.
            return context
                .createPackageContext(
                    pack,
                    Context.CONTEXT_IGNORE_SECURITY,
                ).assets
                .open("appfilter.xml")
                .use { stream ->
                    val parser = Xml.newPullParser()
                    parser.setInput(stream, null)
                    parse(parser)
                }
        }

        fun icon(
            context: Context,
            intent: Intent,
        ): Drawable? {
            if (resources == null) {
                return null
            }
            val pm = context.packageManager
            // Icon packs index by ComponentName (app)
            // If not present try to find what app would open if this intent were launched
            val component =
                intent.component ?: pm
                    .resolveActivity(
                        intent,
                        PackageManager.MATCH_DEFAULT_ONLY,
                    )?.activityInfo
                    ?.let {
                        // Returning ComponentName from activityInfo may lead to incorrect values such as com.android.settings/com.android.settings.homepage.SettingsHomepageActivity
                        // So find in this package, find the first launcher activity
                        val launchIntent =
                            Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_LAUNCHER)
                                setPackage(it.packageName)
                            }

                        pm
                            .queryIntentActivities(
                                launchIntent,
                                PackageManager.MATCH_DEFAULT_ONLY,
                            ).firstOrNull()
                            ?.activityInfo
                            ?.let { activity ->
                                ComponentName(activity.packageName, activity.name)
                            }
                    } ?: return null

            // Use resolvedIcons or drawableNames
            val id =
                resolvedIcons[component] ?: drawableNames
                    .remove(component)
                    ?.let { drawableName ->
                        @SuppressLint("DiscouragedAPI")
                        resources.getIdentifier(drawableName, "drawable", pack).takeIf { it != 0 }
                            ?: resources.getIdentifier(drawableName, "mipmap", pack).takeIf { it != 0 }
                    }?.also {
                        resolvedIcons[component] = it
                    }

            return resources.getDrawable(id ?: return null, null)
        }
    }
}
