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

    // Ranking the buffer means grouping and sorting it, so the result is kept against
    // the exact text it came from. Every pick rewrites that text, which invalidates it.
    private var cachedRaw: String? = null
    private var cachedRanks: Map<String, Int> = emptyMap()

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
     * Every app in the buffer, ranked: the most picked first, and apps picked equally
     * often keep the most recently picked first. Apps that have never been picked are
     * absent, so callers can put them last.
     */
    fun ranks(context: Context): Map<String, Int> {
        val raw = prefs(context).getString(KEY_RECENT, null)
        if (raw == cachedRaw) return cachedRanks
        val recent = parse(raw)
        val counts = recent.groupingBy { it }.eachCount()
        // The stored order is newest first, so an app's first index is its last use.
        val newest = recent.withIndex().associate { (index, key) -> key to index }
        cachedRanks =
            counts.keys
                .sortedWith(compareByDescending<String> { counts.getValue(it) }.thenBy { newest.getValue(it) })
                .withIndex()
                .associate { (rank, key) -> key to rank }
        cachedRaw = raw
        return cachedRanks
    }

    private fun parse(raw: String?): List<String> = raw?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
}
