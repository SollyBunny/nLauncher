package io.github.aurelia.nlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * One receiver for the whole process, telling whoever caches package data that apps
 * were installed, removed or replaced and whatever was resolved has to go.
 */
object PackageWatcher {
    private val callbacks = mutableSetOf<() -> Unit>()
    private var receiver: BroadcastReceiver? = null

    fun onPackagesChanged(
        context: Context,
        callback: () -> Unit,
    ) {
        callbacks += callback
        if (receiver != null) return
        val appContext = context.applicationContext
        val created =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
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
