package io.github.aurelia.nlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

internal data class IntentKey(
    val component: ComponentName? = null,
    val action: String? = null,
    val data: Uri? = null,
    val categories: Set<String> = emptySet(),
)

internal val Intent.key: IntentKey
    get() =
        IntentKey(
            component = component,
            action = action,
            data = data,
            categories = categories?.toSet() ?: emptySet(),
        )

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
        fun new(): Drawable = state.newDrawable(resources).mutate()
    }

    private val cache = mutableMapOf<IntentKey, Entry>()
    private val loading = mutableSetOf<IntentKey>()
    private val waiting = mutableMapOf<IntentKey, MutableList<(Drawable?) -> Unit>>()
    private val failed = mutableSetOf<IntentKey>()

    fun icon(
        context: Context,
        intent: Intent,
        onReady: ((Drawable?) -> Unit)? = null,
    ): Drawable? {
        cache[intent.key]?.let { entry ->
            val icon = entry.new()
            onReady?.invoke(icon)
            return icon
        }
        if (intent.key in failed) {
            // Already known to be impossible: answer straight away rather than queue
            // a waiter nobody is going to come back for.
            onReady?.invoke(null)
            return null
        }
        if (onReady != null) waiting.getOrPut(intent.key) { mutableListOf() }.add(onReady)
        if (loading.add(intent.key)) schedule(context, intent)
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
        intent: Intent,
    ) {
        // Only the resources, never the activity: this runs long after the call that
        // asked for the icon has gone.
        val resources = context.applicationContext.resources
        val iconPack = LauncherConfig.iconPack(context)
        try {
            this.loader.execute {
                val icon =
                    try {
                        val pm = context.applicationContext.packageManager
                        if (iconPack == null) {
                            pm.getActivityIcon(intent)
                        } else {
                            IconPacks.icon(context, iconPack, intent) ?: pm.getActivityIcon(intent)
                        }
                    } catch (e: Exception) {
                        // A broken icon must not take the whole list down with it.
                        Log.w("Icons", "Could not load the icon for $intent", e)
                        null
                    }
                main.post { deliver(intent, resources, icon) }
            }
        } catch (e: RejectedExecutionException) {
            loading.remove(intent.key)
            waiting.remove(intent.key)?.forEach { it(null) }
        }
    }

    /** Store what came back and hand it to everyone waiting for it. */
    private fun deliver(
        intent: Intent,
        resources: Resources,
        icon: Drawable?,
    ) {
        // Gone from the set means [clear] ran while this was loading, so the result
        // belongs to a package that has changed since: dropping it here is what keeps
        // it out of the caches.
        if (!loading.remove(intent.key)) return
        val waiters = waiting.remove(intent.key).orEmpty()
        // A drawable with no constant state cannot be copied, and handing that one
        // instance out to every row is exactly the bug this cache exists to prevent —
        // so it counts as an icon that could not be loaded, same as a null return.
        val state = icon?.constantState
        if (state == null) {
            fail(intent, waiters)
            return
        }
        val entry = Entry(resources, state)
        cache[intent.key] = entry
        waiters.forEach { it(entry.new()) }
    }

    /** Remember [intent] as unloadable and tell everyone waiting that it is never coming. */
    private fun fail(
        intent: Intent,
        waiters: List<(Drawable?) -> Unit>,
    ) {
        failed += intent.key
        waiters.forEach { it(null) }
    }
}
