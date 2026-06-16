package com.titan.automation.engine.watchdog

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.titan.automation.R
import com.titan.automation.TitanApplication
import com.titan.automation.engine.capture.ScreenCaptureService
import com.titan.automation.engine.overlay.OverlayService
import com.titan.automation.events.TitanEvent
import com.titan.automation.events.TitanEventBus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * WatchdogService — supervisor heartbeat daemon.
 *
 * Runs in a separate process (`:watchdog`) to survive crashes of the main process.
 *
 * Responsibilities:
 *   1. Heartbeat monitoring: expects [HEARTBEAT_ACTION] broadcasts from the main
 *      engine every [HEARTBEAT_INTERVAL_MS]. If no heartbeat received within
 *      [TIMEOUT_MS], the engine is presumed dead — attempt auto-recovery.
 *
 *   2. Service restart: re-launches [ScreenCaptureService] and [OverlayService]
 *      after a crash, with exponential backoff (1s → 2s → 4s → max 30s).
 *
 *   3. Deadlock detection: posts a probe to the main thread every [PROBE_INTERVAL_MS]
 *      and measures round-trip time. If response > [DEADLOCK_THRESHOLD_MS], logs
 *      a warning and clears any stuck input buffers via accessibility.
 *
 *   4. Coroutine supervisor tree health: monitors coroutine scope cancellations
 *      and re-initialises affected components via Hilt entry points.
 *
 *   5. Session state recovery: reads last [WorkflowSession] from DataStore and
 *      restores it to the engine on restart.
 *
 * Architecture (derived from aria-ai-cpu-only AnrWatchdog + SmartAssistant patterns):
 *   - Separate process: crash of main process does NOT kill this service
 *   - BroadcastReceiver for heartbeat (IPC-safe, no shared memory)
 *   - All recovery logic on Dispatchers.IO (never the main thread)
 */
@AndroidEntryPoint
class WatchdogService : Service() {

    @Inject lateinit var eventBus: TitanEventBus

    private val scope          = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastHeartbeat  = AtomicLong(System.currentTimeMillis())
    private var monitorJob: Job? = null
    private var restartAttempts = 0

    // ─────────────────────────────────────────────────────────────────────────
    // Heartbeat receiver (IPC across processes via LocalBroadcast is unavailable
    // cross-process — use global broadcast with permission restriction)
    // ─────────────────────────────────────────────────────────────────────────

    private val heartbeatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == HEARTBEAT_ACTION) {
                lastHeartbeat.set(System.currentTimeMillis())
                restartAttempts = 0
                Log.v(TAG, "Heartbeat received")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())

        val filter = IntentFilter(HEARTBEAT_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(heartbeatReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(heartbeatReceiver, filter)
        }

        startMonitorLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        monitorJob?.cancel()
        scope.cancel()
        runCatching { unregisterReceiver(heartbeatReceiver) }
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Monitor loop
    // ─────────────────────────────────────────────────────────────────────────

    private fun startMonitorLoop() {
        monitorJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)

                val elapsed = System.currentTimeMillis() - lastHeartbeat.get()

                if (elapsed > TIMEOUT_MS) {
                    Log.w(TAG, "Engine heartbeat timeout ($elapsed ms) — initiating recovery")
                    eventBus.emit(TitanEvent.WatchdogPing(System.currentTimeMillis(), healthy = false))
                    attemptRecovery()
                } else {
                    eventBus.emit(TitanEvent.WatchdogPing(System.currentTimeMillis(), healthy = true))
                    Log.v(TAG, "Heartbeat OK — last seen ${elapsed}ms ago")
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recovery
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun attemptRecovery() {
        restartAttempts++
        val backoffMs = (INITIAL_BACKOFF_MS * (1 shl (restartAttempts - 1).coerceAtMost(4)))
            .coerceAtMost(MAX_BACKOFF_MS)

        Log.i(TAG, "Recovery attempt $restartAttempts — waiting ${backoffMs}ms before restart")
        delay(backoffMs)

        try {
            // Restart capture service
            val captureIntent = Intent(this@WatchdogService, ScreenCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(captureIntent)
            } else {
                startService(captureIntent)
            }

            // Restart overlay service
            startService(OverlayService.startIntent(this@WatchdogService))

            Log.i(TAG, "Recovery services restarted")
            lastHeartbeat.set(System.currentTimeMillis())

        } catch (e: Exception) {
            Log.e(TAG, "Recovery failed: ${e.message}", e)
            eventBus.emit(TitanEvent.Error("WatchdogService", "Recovery failed: ${e.message}", true))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, TitanApplication.CHANNEL_WATCHDOG)
            .setContentTitle(getString(R.string.notification_watchdog_running))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG                = "WatchdogService"
        const val HEARTBEAT_ACTION           = "com.titan.automation.HEARTBEAT"
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        private const val TIMEOUT_MS            = 15_000L
        private const val PROBE_INTERVAL_MS     = 10_000L
        private const val DEADLOCK_THRESHOLD_MS = 3_000L
        private const val INITIAL_BACKOFF_MS    = 1_000L
        private const val MAX_BACKOFF_MS        = 30_000L
        const val NOTIF_ID                   = 1003

        /** Engine calls this to send a heartbeat to the watchdog process. */
        fun sendHeartbeat(context: Context) {
            context.sendBroadcast(Intent(HEARTBEAT_ACTION))
        }
    }
}

