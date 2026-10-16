package io.github.aurelia.nlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

/**
 * The apps that can be launched, listed once and kept in memory.
 *
 * The labels are handed over straight away so the results can be drawn immediately;
 * the icons are loaded on a background thread (see [Icons]) and [apps] reports back
 * through [onIconsLoaded] once they are all in, which is when the list needs a single
 * refresh. The list is dropped whenever packages change.
 */
object AppCatalog {
    private var cached: List<AppEntry>? = null
    private var watching = false

    /** One launchable app: what its rows show, and what picking it launches. */
    class AppEntry(
        val label: String,
        val intent: Intent,
    ) {
        /** Stable identity for the icon cache. */
        val key: String = intent.toUri(Intent.URI_INTENT_SCHEME)
    }

    /**
     * Every launchable app, in label order, resolving the list on the first call.
     *
     * [onIconsLoaded] is called once the icons have finished loading in the
     * background, so a list that drew without them can refresh itself.
     */
    fun apps(
        context: Context,
        onIconsLoaded: () -> Unit,
    ): List<AppEntry> {
        cached?.let { return it }
        val pm = context.packageManager
        val entries =
            pm
                .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .asSequence()
                .map { it.activityInfo }
                // Keep the launcher's own home activity out of the picker list.
                .filter { it.packageName != context.packageName }
                .distinctBy { it.packageName to it.name }
                .map {
                    AppEntry(
                        label = it.loadLabel(pm).toString(),
                        intent =
                            Intent(Intent.ACTION_MAIN)
                                .addCategory(Intent.CATEGORY_LAUNCHER)
                                .setComponent(ComponentName(it.packageName, it.name)),
                    )
                }.sortedBy { it.label.lowercase() }
                .toList()
        cached = entries
        if (!watching) {
            watching = true
            PackageWatcher.onPackagesChanged(context) { cached = null }
        }
        loadIcons(context, entries, onIconsLoaded)
        return entries
    }

    /** [entry]'s icon: the cached one, or null while it is still loading. */
    fun icon(
        context: Context,
        entry: AppEntry,
    ): Drawable? {
        val loader = loader(context, entry) ?: return null
        return Icons.icon(context, entry.key, loader)
    }

    private fun loadIcons(
        context: Context,
        entries: List<AppEntry>,
        onLoaded: () -> Unit,
    ) {
        if (entries.isEmpty()) {
            onLoaded()
            return
        }
        var pending = entries.size
        for (entry in entries) {
            // Icons already cached count straight away, the rest report in as they
            // land, and an entry with nothing to load reports in right now, so the
            // count always reaches zero.
            val loader = loader(context, entry)
            if (loader == null) {
                if (--pending == 0) onLoaded()
                continue
            }
            Icons.icon(context, entry.key, loader) {
                if (--pending == 0) onLoaded()
            }
        }
    }

    /**
     * How to load [entry]'s icon on a background thread: from the application context
     * and the entry alone, so the task never holds on to a screen.
     */
    private fun loader(
        context: Context,
        entry: AppEntry,
    ): (() -> Drawable?)? {
        val component = entry.intent.component ?: return null
        val pm = context.applicationContext.packageManager
        return { pm.getActivityIcon(component) }
    }
}
