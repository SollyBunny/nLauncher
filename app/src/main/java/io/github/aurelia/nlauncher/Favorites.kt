package io.github.aurelia.nlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

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

    /**
     * [favorite]'s icon: the cached one right away, or null while it is still loading,
     * in which case [onReady] gets it once it lands.
     */
    fun icon(
        context: Context,
        favorite: FavoriteApp,
        onReady: (Drawable?) -> Unit,
    ): Drawable? {
        watch(context)
        // The loader must not outlive the activity, so it works from the application
        // context and a private copy of the intent, and never touches either again.
        val pm = context.applicationContext.packageManager
        val intent = Intent(favorite.intent)
        val component = intent.component
        return Icons.icon(
            context,
            favorite.key,
            {
                // The pack first, the system's own icon if it has nothing for this app.
                val packed = component?.let { IconPacks.icon(context, it) }
                packed ?: pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.loadIcon(pm)
            },
            onReady,
        )
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
