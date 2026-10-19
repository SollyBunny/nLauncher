package io.github.aurelia.nlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * An app pinned to the favorites list. Nothing is resolved here — [Favorites] does
 * that, so the list is not tied to any one ROM and nothing is resolved twice.
 */
class FavoriteApp(
    val intent: Intent,
) {
    /** Stable identity for the label and icon caches. */
    val key: String = intent.toUri(Intent.URI_INTENT_SCHEME)
}

/**
 * The favorite apps, resolved through the [PackageManager] and then remembered.
 *
 * Labels are cheap enough to look up on the spot, and cost nothing at all the second
 * time; icons are loaded on a background thread (see [Icons]), because a launcher
 * redraws the home screen on every Home press. Anything resolved is dropped again
 * when packages change.
 */
object Favorites {
    private val labels = mutableMapOf<String, CharSequence>()
    private var watching = false

    /** [favorite]'s label, resolved once and cached from then on. */
    fun label(
        context: Context,
        favorite: FavoriteApp,
    ): CharSequence {
        watch(context)
        return labels.getOrPut(favorite.key) { resolveLabel(context.packageManager, favorite.intent) }
    }

    private fun resolveLabel(
        pm: PackageManager,
        intent: Intent,
    ): CharSequence {
        val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.loadLabel(pm)
            ?: intent.component?.packageName
            ?: intent.action
            ?: "Unknown App"
    }

    private fun watch(context: Context) {
        if (watching) return
        watching = true
        // Only the labels are this cache's to drop; the icon cache is cleared by
        // PackageWatcher itself, which is the only thing that knows a package changed.
        PackageWatcher.onPackagesChanged(context) { labels.clear() }
    }
}
