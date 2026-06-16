package com.titan.automation.engine.watchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BootReceiver — restarts the WatchdogService after device reboot.
 *
 * Declared in AndroidManifest with RECEIVE_BOOT_COMPLETED permission.
 * Handles both cold boot (ACTION_BOOT_COMPLETED) and quick-boot/resume
 * (ACTION_MY_PACKAGE_REPLACED) so upgrades also restart the watchdog.
 *
 * The watchdog itself is responsible for re-launching the MacroEngine
 * if a persisted session checkpoint is found in DataStore.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.i(TAG, "Boot event [${intent.action}] — starting WatchdogService")
                startWatchdog(context)
            }
            else -> {
                Log.d(TAG, "Unhandled intent action: ${intent.action}")
            }
        }
    }

    private fun startWatchdog(context: Context) {
        val watchdogIntent = Intent(context, WatchdogService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(watchdogIntent)
            } else {
                context.startService(watchdogIntent)
            }
            Log.i(TAG, "WatchdogService start request sent")
        }.onFailure { e ->
            Log.e(TAG, "Failed to start WatchdogService from boot", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
