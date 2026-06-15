package com.titan.automation.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PerformanceMonitor — real-time JVM + native memory, CPU, and GC metrics.
 *
 * Polls every [POLL_INTERVAL_MS] on a background coroutine and exposes live
 * [StateFlow] snapshots for the overlay HUD and telemetry.
 *
 * Metrics tracked:
 *   - JVM heap used / total / max (MB)
 *   - Native heap used (MB) via [Debug.getNativeHeapAllocatedSize]
 *   - GC pause count delta (approximated via [Debug.getRuntimeStat])
 *   - App PSS (MB) via [ActivityManager.getProcessMemoryInfo] (sampled every 5s)
 *   - Background thread count (live)
 *
 * No NDK required — all APIs available on API 29+.
 */
@Singleton
class PerformanceMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null

    private val _snapshot = MutableStateFlow(PerformanceSnapshot())
    val snapshot: StateFlow<PerformanceSnapshot> = _snapshot.asStateFlow()

    private val runtime   = Runtime.getRuntime()
    private val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    // PSS sampling — expensive, so rate-limited
    @Volatile private var lastPssSampleMs = 0L
    @Volatile private var cachedPssMb     = 0f

    fun start() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                _snapshot.value = sample()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun sample(): PerformanceSnapshot {
        val heapUsed  = (runtime.totalMemory() - runtime.freeMemory()) / MB
        val heapTotal = runtime.totalMemory() / MB
        val heapMax   = runtime.maxMemory() / MB
        val nativeUsed = Debug.getNativeHeapAllocatedSize() / MB

        // GC approximation via runtime stats (available API 23+)
        val gcCount = runCatching {
            Debug.getRuntimeStat("art.gc.gc-count").toLong()
        }.getOrDefault(0L)

        // PSS — costly IPC, only refresh every PSS_INTERVAL_MS
        val now = System.currentTimeMillis()
        if (now - lastPssSampleMs > PSS_INTERVAL_MS) {
            lastPssSampleMs = now
            runCatching {
                val pid   = Process.myPid()
                val info  = actManager.getProcessMemoryInfo(intArrayOf(pid))
                cachedPssMb = info.firstOrNull()?.totalPss?.let { it / 1024f } ?: 0f
            }
        }

        val threadCount = Thread.activeCount()

        return PerformanceSnapshot(
            heapUsedMb     = heapUsed.toFloat(),
            heapTotalMb    = heapTotal.toFloat(),
            heapMaxMb      = heapMax.toFloat(),
            nativeUsedMb   = nativeUsed.toFloat(),
            pssMb          = cachedPssMb,
            gcCount        = gcCount,
            threadCount    = threadCount,
            timestampMs    = now
        )
    }

    /** Returns true if JVM heap usage exceeds [WARNING_HEAP_FRACTION] of max. */
    fun isHeapPressure(): Boolean {
        val s = _snapshot.value
        return s.heapUsedMb / s.heapMaxMb.coerceAtLeast(1f) > WARNING_HEAP_FRACTION
    }

    companion object {
        private const val TAG              = "PerformanceMonitor"
        private const val POLL_INTERVAL_MS = 1_000L
        private const val PSS_INTERVAL_MS  = 5_000L
        private const val MB               = 1_048_576L
        private const val WARNING_HEAP_FRACTION = 0.80f
    }
}

data class PerformanceSnapshot(
    val heapUsedMb  : Float = 0f,
    val heapTotalMb : Float = 0f,
    val heapMaxMb   : Float = 0f,
    val nativeUsedMb: Float = 0f,
    val pssMb       : Float = 0f,
    val gcCount     : Long  = 0L,
    val threadCount : Int   = 0,
    val timestampMs : Long  = 0L
) {
    val heapUsagePercent: Float
        get() = if (heapMaxMb > 0) heapUsedMb / heapMaxMb * 100f else 0f
}
