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
        return Icons.icon(context, favorite.key, { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.loadIcon(pm) }, onReady)
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
        PackageWatcher.onPackagesChanged(context) {
            labels.clear()
            Icons.clear()
        }
    }
}
