package io.github.aurelia.nlauncher

import android.content.Intent

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
