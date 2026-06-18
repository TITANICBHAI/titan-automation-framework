package com.titan.automation.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * App-wide coroutine scope qualifiers and managed scopes.
 *
 * Scopes defined:
 *   [ApplicationScope]  — survives the entire app lifecycle; use for engine-level work
 *   [CaptureScope]      — dedicated single-thread for zero-copy frame acquisition
 *   [InferenceScope]    — CPU-bound ML inference, Dispatchers.Default bounded to 2 threads
 *   [VisionScope]       — OpenCV processing, Dispatchers.Default
 *
 * All scopes use [SupervisorJob] so a child failure never cancels siblings.
 */

@Qualifier @Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Qualifier @Retention(AnnotationRetention.BINARY)
annotation class CaptureScope

@Qualifier @Retention(AnnotationRetention.BINARY)
annotation class InferenceScope

@Qualifier @Retention(AnnotationRetention.BINARY)
annotation class VisionScope

@Qualifier @Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

@Qualifier @Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier @Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

// ── Scope provider ────────────────────────────────────────────────────────────

/**
 * Singleton holder for all named coroutine scopes.
 * Injected directly into services / engines that need a long-lived scope.
 */
@Singleton
class TitanCoroutineScopes @Inject constructor() {

    /** App-level supervisor scope — cancel only via [cancelAll]. */
    @ApplicationScope
    val application: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Capture scope — single dedicated thread so frame acquisition never
     * competes with vision/RL processing.
     */
    @CaptureScope
    val capture: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    /**
     * Inference scope — bounded to 2 parallel coroutines to avoid thermal spike
     * when multiple TFLite/RL operations fire simultaneously.
     */
    @InferenceScope
    val inference: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(2))

    /** Vision scope — OpenCV operations on Dispatchers.Default. */
    @VisionScope
    val vision: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun cancelAll() {
        application.coroutineContext[Job]?.cancel()
        capture.coroutineContext[Job]?.cancel()
        inference.coroutineContext[Job]?.cancel()
        vision.coroutineContext[Job]?.cancel()
    }
}

// ── Dispatcher providers (for testing / injection) ───────────────────────────

@Singleton
class TitanDispatchers @Inject constructor() {
    @MainDispatcher    val main:    CoroutineDispatcher = Dispatchers.Main
    @DefaultDispatcher val default: CoroutineDispatcher = Dispatchers.Default
    @IoDispatcher      val io:      CoroutineDispatcher = Dispatchers.IO
}
