package io.github.aurelia.nlauncher

import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * App icons, off the main thread.
 *
 * An icon is an asset plus density scaling, which is far too slow to load while a
 * screen is drawing — and this is a launcher, which draws a screen on every Home
 * press. So nothing here ever calls the [android.content.pm.PackageManager] on the
 * main thread: ask for an icon, get whatever is cached (usually the icon itself,
 * instantly), and get [onReady] once the first load lands. Every key is loaded at
 * most once no matter how many rows ask for it.
 *
 * Only the main thread touches the caches; the loader runs on a single background
 * thread and hands its result back through the main handler.
 */
object Icons {
    private val loader =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "icons").apply { isDaemon = true }
        }
    private val main = Handler(Looper.getMainLooper())

    /**
     * One cached icon, kept only as a source to draw from: a drawable is stateful, and
     * one instance shared between views ends up drawn as the wrong icon (or not
     * redrawn at all) as soon as two of them show it. [new] hands out a private copy.
     *
     * There is deliberately no fallback to a cached drawable, so an icon with no
     * [Drawable.ConstantState] to copy from is simply not cacheable — see [deliver].
     */
    private class Entry(
        private val resources: Resources,
        private val state: Drawable.ConstantState,
    ) {
        fun new(): Drawable? = state.newDrawable(resources)?.mutate()
    }

    private val cache = mutableMapOf<String, Entry>()
    private val loading = mutableSetOf<String>()
    private val waiting = mutableMapOf<String, MutableList<(Drawable?) -> Unit>>()

    /** Keys whose icon could not be loaded, so a broken icon is not retried per row. */
    private val failed = mutableSetOf<String>()

    /**
     * The icon for [key], or null while it is still being loaded — in which case
     * [loader] runs in the background. Callers that just want an icon to draw use
     * this; callers that must be told when it arrives use the overload below.
     */
    fun icon(
        context: Context,
        key: String,
        loader: () -> Drawable?,
    ): Drawable? = request(context, key, loader, null)

    /**
     * As above, and [onReady] is called with the icon once it is there — right away
     * when it was already cached, on the main thread either way. [onReady] is also
     * called with null if the icon cannot be loaded, so callers can count on it.
     */
    fun icon(
        context: Context,
        key: String,
        loader: () -> Drawable?,
        onReady: (Drawable?) -> Unit,
    ): Drawable? = request(context, key, loader, onReady)

    private fun request(
        context: Context,
        key: String,
        loader: () -> Drawable?,
        onReady: ((Drawable?) -> Unit)?,
    ): Drawable? {
        cache[key]?.let { entry ->
            val icon = entry.new()
            onReady?.invoke(icon)
            return icon
        }
        if (key in failed) {
            // Already known to be impossible: answer straight away rather than queue
            // a waiter nobody is going to come back for.
            onReady?.invoke(null)
            return null
        }
        if (onReady != null) waiting.getOrPut(key) { mutableListOf() }.add(onReady)
        if (loading.add(key)) schedule(context, key, loader)
        return null
    }

    /**
     * Forget every icon, e.g. because a package changed. Loads already on their way
     * are let go of as well: what they found describes the package as it was *before*
     * the change, so it must not land in the fresh caches. That is what stops an app
     * that has just been updated going on showing the icon it had before.
     */
    fun clear() {
        cache.clear()
        failed.clear()
        loading.clear()
        val dropped = waiting.values.flatten()
        waiting.clear()
        // Nobody is going to come back for these, but a caller counting down to
        // something still has to hear that it is not going to happen.
        dropped.forEach { it(null) }
    }

    private fun schedule(
        context: Context,
        key: String,
        loader: () -> Drawable?,
    ) {
        // Only the resources, never the activity: this runs long after the call that
        // asked for the icon has gone.
        val resources = context.applicationContext.resources
        try {
            this.loader.execute {
                val icon =
                    try {
                        loader()
                    } catch (e: Exception) {
                        // A broken icon must not take the whole list down with it.
                        Log.w("Icons", "Could not load the icon for $key", e)
                        null
                    }
                main.post { deliver(key, resources, icon) }
            }
        } catch (e: RejectedExecutionException) {
            loading.remove(key)
            waiting.remove(key)?.forEach { it(null) }
        }
    }

    /** Store what came back and hand it to everyone waiting for it. */
    private fun deliver(
        key: String,
        resources: Resources,
        icon: Drawable?,
    ) {
        // Gone from the set means [clear] ran while this was loading, so the result
        // belongs to a package that has changed since: dropping it here is what keeps
        // it out of the caches.
        if (!loading.remove(key)) return
        val waiters = waiting.remove(key).orEmpty()
        // A drawable with no constant state cannot be copied, and handing that one
        // instance out to every row is exactly the bug this cache exists to prevent —
        // so it counts as an icon that could not be loaded, same as a null return.
        val state = icon?.constantState
        if (state == null) {
            fail(key, waiters)
            return
        }
        val entry = Entry(resources, state)
        cache[key] = entry
        waiters.forEach { it(entry.new()) }
    }

    /** Remember [key] as unloadable and tell everyone waiting that it is never coming. */
    private fun fail(
        key: String,
        waiters: List<(Drawable?) -> Unit>,
    ) {
        failed += key
        waiters.forEach { it(null) }
    }
}
