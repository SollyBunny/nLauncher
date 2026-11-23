package io.github.aurelia.nlauncher

import android.content.Context

/**
 * The apps picked most recently, kept in a fixed-length buffer of the last [LIMIT]
 * picks, newest first. An app that has not been opened in a while drops out on its
 * own, which is what makes the recent section of the search screen reorder itself.
 *
 * The buffer lives in shared preferences next to the rest of the launcher's state,
 * so it survives the activity being recreated.
 */
object AppUsage {
    private const val LIMIT = 100

    private val recent = SettingStringList("recent_app_usage")

    /** Note that [key] was picked, dropping the oldest pick once the buffer is full. */
    fun record(
        context: Context,
        key: String,
    ) {
        recent.set(context, (listOf(key) + recent.get(context)).take(LIMIT))
    }

    /** The apps in the recent buffer, newest first, each once. */
    fun recentKeys(context: Context): List<String> = recent.get(context).distinct()
}
