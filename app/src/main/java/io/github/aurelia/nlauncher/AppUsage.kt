package io.github.aurelia.nlauncher

import android.content.Context

/**
 * How often each app has been picked, taken from a fixed-length buffer of the most
 * recent picks.
 *
 * The buffer holds the last [LIMIT] picks, newest first, so an app that has not been
 * opened in a while drops out on its own. It lives in shared preferences next to the
 * rest of the launcher's state, so it survives the activity being recreated.
 */
object AppUsage {
    private const val PREFS = "app_usage"
    private const val KEY_RECENT = "recent"
    private const val LIMIT = 100

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Note that [key] was picked, dropping the oldest pick once the buffer is full. */
    fun record(
        context: Context,
        key: String,
    ) {
        val prefs = prefs(context)
        val recent = parse(prefs.getString(KEY_RECENT, null))
        val next = (listOf(key) + recent).take(LIMIT)
        prefs.edit().putString(KEY_RECENT, next.joinToString("\n")).apply()
    }

    /**
     * The apps in the recent buffer, newest first, each once.
     *
     * The buffer holds the last [LIMIT] picks, so this is both the recent section and
     * its order: an app that has not been opened in a while drops out on its own.
     */
    fun recentKeys(context: Context): List<String> = parse(prefs(context).getString(KEY_RECENT, null)).distinct()

    private fun parse(raw: String?): List<String> = raw?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
}
