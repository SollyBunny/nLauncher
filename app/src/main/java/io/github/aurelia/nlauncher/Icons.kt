package io.github.aurelia.nlauncher

import android.content.Context
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

    private val cache = mutableMapOf<String, Drawable>()
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
        cache[key]?.let { return it.also { icon -> onReady?.invoke(icon) } }
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

    /** Forget every icon, e.g. because a package changed. */
    fun clear() {
        cache.clear()
        failed.clear()
    }

    private fun schedule(
        context: Context,
        key: String,
        loader: () -> Drawable?,
    ) {
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
                main.post { deliver(key, icon) }
            }
        } catch (e: RejectedExecutionException) {
            loading.remove(key)
            waiting.remove(key)?.forEach { it(null) }
        }
    }

    /** Store what came back and hand it to everyone waiting for it. */
    private fun deliver(
        key: String,
        icon: Drawable?,
    ) {
        loading.remove(key)
        if (icon == null) {
            failed += key
        } else {
            cache[key] = icon
        }
        waiting.remove(key)?.forEach { it(icon) }
    }
}
