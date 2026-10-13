package io.github.aurelia.nlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/**
 * An app pinned to the favorites list. Everything is resolved lazily through the
 * [PackageManager] so the list is not tied to any one ROM.
 */
class FavoriteApp(
    val intent: Intent,
) {
    fun resolve(context: Context): ResolvedFavorite {
        val pm = context.packageManager
        val info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val icon = info?.loadIcon(pm) ?: pm.defaultActivityIcon
        val label =
            info?.loadLabel(pm)
                ?: intent.component?.packageName
                ?: intent.action
                ?: "App"
        return ResolvedFavorite(Intent(intent), icon, label)
    }
}

/** A [FavoriteApp] with its icon and label already loaded. */
class ResolvedFavorite(
    val intent: Intent,
    /** Null when the package manager has no icon to offer at all. */
    val icon: Drawable?,
    val label: CharSequence,
)
