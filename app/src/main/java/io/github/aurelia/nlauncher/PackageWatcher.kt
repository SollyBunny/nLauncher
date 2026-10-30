package io.github.aurelia.nlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * Watches package changes (install, uninstall, modification)
 */
object PackageWatcher {
    private val callbacks = mutableSetOf<() -> Unit>()
    private var receiver: BroadcastReceiver? = null

    // Bumped whenever a package is installed, removed or replaced.
    var revision: Int = 0
        private set

    fun onPackagesChanged(
        context: Context,
        callback: () -> Unit,
    ) {
        callbacks += callback
        if (receiver != null) return
        val appContext = context.applicationContext
        val created =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    revision += 1
                    // The icon cache is dropped here rather than by whichever cache
                    // happens to hold icons: it is the one thing that has to go on any
                    // package change, and leaving it to a listener meant it silently
                    // stopped happening whenever nothing had resolved a favorite yet.
                    Icons.clear()
                    for (callback in callbacks.toList()) callback()
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(created, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(created, filter)
        }
        receiver = created
    }
}
