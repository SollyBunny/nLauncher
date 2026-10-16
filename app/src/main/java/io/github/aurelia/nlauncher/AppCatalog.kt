package io.github.aurelia.nlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * The apps that can be launched, listed once and kept in memory.
 *
 * The list is resolved on a background thread, and the launcher asks for it as soon
 * as it starts ([LauncherActivity] calls [prewarm]), so opening the search finds
 * the labels already there instead of paying for a package query on the spot. [apps]
 * hands the list to [onReady] on the main thread — immediately when it is already
 * cached, otherwise as soon as the resolver has finished with it. Icons are left
 * alone: they stream in through [Icons] and [onIconsLoaded] is the refresh to run
 * once they have all landed. The list is dropped whenever packages change.
 */
object AppCatalog {
    /** The package query never races itself: one background thread does all of it. */
    private val resolver =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "apps").apply { isDaemon = true }
        }
    private val main = Handler(Looper.getMainLooper())

    private var cached: List<AppEntry>? = null
    private var building = false
    private var watching = false

    /** Bumped when packages change, so a list already being resolved is thrown away. */
    private var generation = 0

    /** Told about the list once it has been resolved; one shot per waiter. */
    private val ready = mutableListOf<(List<AppEntry>) -> Unit>()

    /** The refresh to run once the current list's icons have all landed. */
    private var iconsRefresh: () -> Unit = {}

    /** One launchable app: what its rows show, and what picking it launches. */
    class AppEntry(
        val label: String,
        val intent: Intent,
    ) {
        /** Stable identity for the icon cache. */
        val key: String = intent.toUri(Intent.URI_INTENT_SCHEME)
    }

    /**
     * Resolve the app list in the background before anybody asks for it, so the
     * first search opens on a list that is already there.
     */
    fun prewarm(context: Context) {
        apps(context, onReady = {})
    }

    /**
     * Every launchable app, in label order: [onReady] runs on the main thread with
     * the list — right away when it is cached, and otherwise as soon as the resolver
     * has finished. [onIconsLoaded] is the refresh to run once the icons have all
     * landed, so a list that drew without them can refresh itself.
     */
    fun apps(
        context: Context,
        onReady: (List<AppEntry>) -> Unit,
        onIconsLoaded: () -> Unit = {},
    ) {
        iconsRefresh = onIconsLoaded
        val existing = cached
        if (existing != null) {
            onReady(existing)
            return
        }
        ready += onReady
        resolve(context)
    }

    /** [entry]'s icon: the cached one, or null while it is still loading. */
    fun icon(
        context: Context,
        entry: AppEntry,
    ): Drawable? {
        val loader = loader(context, entry) ?: return null
        return Icons.icon(context, entry.key, loader)
    }

    // ---- Resolving -------------------------------------------------------

    /** Start resolving the list, unless a resolution is already on its way. */
    private fun resolve(context: Context) {
        if (building) return
        building = true
        watch(context)
        // The task works from the application context and the entries alone, so it
        // never holds on to a screen.
        val app = context.applicationContext
        val started = generation
        resolver.execute {
            val entries = listApps(app)
            main.post { publish(app, entries, started) }
        }
    }

    /** Every launchable app, in label order — the whole point, off the main thread. */
    private fun listApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        return pm
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
    }

    /** Store the resolved list on the main thread and hand it to everyone waiting. */
    private fun publish(context: Context, entries: List<AppEntry>, started: Int) {
        building = false
        if (started != generation) {
            // Packages changed while the list was being resolved: resolve it again.
            resolve(context)
            return
        }
        cached = entries
        loadIcons(context, entries)
        val waiters = ready.toList()
        ready.clear()
        waiters.forEach { it(entries) }
    }

    /** Drop the list (and re-resolve it on the next ask) when packages change. */
    private fun watch(context: Context) {
        if (watching) return
        watching = true
        PackageWatcher.onPackagesChanged(context) {
            generation += 1
            cached = null
        }
    }

    private fun loadIcons(
        context: Context,
        entries: List<AppEntry>,
    ) {
        if (entries.isEmpty()) {
            iconsRefresh()
            return
        }
        var pending = entries.size
        for (entry in entries) {
            // Icons already cached count straight away, the rest report in as they
            // land, and an entry with nothing to load reports in right now, so the
            // count always reaches zero.
            val loader = loader(context, entry)
            if (loader == null) {
                if (--pending == 0) iconsRefresh()
                continue
            }
            Icons.icon(context, entry.key, loader) {
                if (--pending == 0) iconsRefresh()
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
