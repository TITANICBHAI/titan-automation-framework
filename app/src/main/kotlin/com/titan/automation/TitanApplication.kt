package com.titan.automation

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.titan.automation.core.TitanLogger
import com.titan.automation.engine.capture.NativeBridge
import com.titan.automation.engine.watchdog.AnrWatchdog
import com.titan.automation.monitoring.CrashRecoveryManager
import com.titan.automation.startup.FirstRunSeeder
import com.titan.automation.telemetry.TelemetryManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class TitanApplication : Application() {

    @Inject lateinit var telemetry: TelemetryManager
    @Inject lateinit var firstRunSeeder: FirstRunSeeder
    @Inject lateinit var crashRecovery: CrashRecoveryManager
    @Inject lateinit var logger: TitanLogger

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** In-process ANR monitor — starts before Hilt injects are ready. */
    private lateinit var anrWatchdog: AnrWatchdog

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()

        // ANR watchdog: start immediately so we capture hangs during Hilt init
        anrWatchdog = AnrWatchdog(
            logDir      = filesDir,
            thresholdMs = 5_000L,
            pollMs      = 1_000L
        ).also {
            it.onAnrDetected = { file ->
                Log.e(TAG, "ANR captured → ${file.name} (${file.length()} bytes)")
            }
            it.start()
        }

        // Crash recovery: installs uncaught-exception handler + heartbeat watcher
        crashRecovery.install(object : CrashRecoveryManager.RecoveryCallback {
            override fun onCrashDetected(crashInfo: String) {
                Log.e(TAG, "Crash detected — initiating restart")
                telemetry.log("CrashRecovery", crashInfo.take(500))
            }
            override fun onStallDetected(stalledMs: Long) {
                Log.w(TAG, "Engine stalled for ${stalledMs}ms — watchdog notified")
            }
            override fun onGivingUp(crashCount: Int) {
                Log.e(TAG, "Giving up after $crashCount crashes — showing error UI")
            }
        })

        // Initialise structured logger — points file sink at app's files dir
        logger.init(filesDir)
        logger.i(TAG, "TitanApplication started (filesDir=${filesDir.absolutePath})")

        telemetry.init()

        // Load JNI bridge on a background thread — never blocks onCreate
        appScope.launch(Dispatchers.IO) {
            NativeBridge.loadLibrary()
        }

        // Seed bundled example workflows into Room on first install only
        firstRunSeeder.seedIfFirstRun(appScope)
    }

    override fun onTerminate() {
        anrWatchdog.stop()
        crashRecovery.uninstall()
        super.onTerminate()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        listOf(
            NotificationChannel(
                CHANNEL_ENGINE,
                getString(R.string.notification_channel_engine),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) },
            NotificationChannel(
                CHANNEL_CAPTURE,
                getString(R.string.notification_channel_capture),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) },
            NotificationChannel(
                CHANNEL_WATCHDOG,
                getString(R.string.notification_channel_watchdog),
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
        ).forEach { nm.createNotificationChannel(it) }
    }

    companion object {
        private const val TAG          = "TitanApplication"
        const val CHANNEL_ENGINE   = "titan_engine"
        const val CHANNEL_CAPTURE  = "titan_capture"
        const val CHANNEL_WATCHDOG = "titan_watchdog"
    }
}
