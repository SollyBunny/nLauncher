package io.github.aurelia.nlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.Locale
import java.util.concurrent.Executors

class AppEntry(
    val label: String,
    val intent: Intent,
) {
    /** Stable identity for the icon cache. **/
    val key: String = intent.toUri(Intent.URI_INTENT_SCHEME)

    /** Normalized search label. **/
    val searchLabel: String = label.lowercase(Locale.ROOT)
}

data class AppCatalog(
    val revision: Int,
    val apps: List<AppEntry>,
)

/**
 * Keeps up to date catalog of launchable apps
 */
object AppCatalogWatcher {
    /** The package query never races itself: one background thread does all of it. */
    private val resolver =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "apps").apply { isDaemon = true }
        }
    private val main = Handler(Looper.getMainLooper())
    private var resolving: Boolean = false

    private var cached: AppCatalog? = null
    private val callbacks = mutableSetOf<(AppCatalog?) -> Unit>()

    fun init(context: Context) {
        // Resolve now to prevent wait when searching
        resolve(context)
        PackageWatcher.onPackagesChanged(context) {
            resolve(context)
        }
    }

    fun watchAppCatalog(
        context: Context,
        callback: (AppCatalog?) -> Unit,
    ) {
        callbacks += callback
        if (cached != null) {
            callback(cached)
        }
    }

    // ---- Resolving -------------------------------------------------------

    private fun resolve(context: Context) {
        if (resolving) return
        resolving = true
        cached = null
        val app = context.applicationContext
        // Do this work off the main thread
        resolver.execute {
            try {
                val revision = PackageWatcher.revision
                val apps = listApps(app)
                val catalog = AppCatalog(revision, apps)
                main.post {
                    resolving = false
                    if (catalog.revision != PackageWatcher.revision) {
                        resolve(context)
                        return@post
                    }
                    cached = catalog
                    callbacks.toList().forEach { it(catalog) }
                }
            } catch (e: Exception) {
                main.post {
                    resolving = false
                }
            }
        }
    }

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
            }.sortedBy { it.searchLabel }
            .toList()
    }
}
