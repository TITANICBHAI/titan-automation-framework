package com.titan.automation.engine.accessibility

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * AntiDetectionJitter — introduces human-like micro-variance into touch events.
 *
 * Two independent variance channels:
 *   1. Spatial  — Gaussian offset (σ configurable per workflow) applied to X/Y coordinates
 *   2. Temporal — uniform random delay applied before gesture dispatch
 *
 * Both sigma and delay range are configurable per-workflow via the workflow JSON
 * (jitter_sigma and delay_before_ms / delay_after_ms fields on ActionDefinition).
 *
 * Implementation uses Box-Muller transform for true Gaussian distribution.
 */
@Singleton
class AntiDetectionJitter @Inject constructor() {

    /** Default spatial jitter standard deviation in normalised [0..1] units (~3px on 1080p). */
    var defaultSigma: Float = 0.003f

    /** Default timing variance window in milliseconds. */
    var defaultMinDelayMs: Long = 15L
    var defaultMaxDelayMs: Long = 55L

    /** Hard clamp: jitter cannot exceed this fraction of screen dimension. */
    private val maxOffset: Float = 0.015f

    // ── Spatial jitter ────────────────────────────────────────────────────────

    /**
     * Apply Gaussian jitter to normalised coordinates.
     * Uses Box-Muller transform: no dependency on java.lang.Math.random() state.
     * Clamps output to [0, 1] and to ±[maxOffset].
     *
     * @param nx    Normalised X coordinate [0..1]
     * @param ny    Normalised Y coordinate [0..1]
     * @param sigma Std deviation in normalised units (0 = no jitter)
     * @return Pair(jitteredNx, jitteredNy)
     */
    fun apply(nx: Float, ny: Float, sigma: Float = defaultSigma): Pair<Float, Float> {
        if (sigma <= 0f) return Pair(nx, ny)

        val (dx, dy) = gaussianPair(sigma)
        return Pair(
            (nx + dx.coerceIn(-maxOffset, maxOffset)).coerceIn(0f, 1f),
            (ny + dy.coerceIn(-maxOffset, maxOffset)).coerceIn(0f, 1f)
        )
    }

    /**
     * Apply jitter in pixel space for APIs that work with absolute pixels.
     * [screenWidth] / [screenHeight] used for clamping.
     */
    fun applyPixels(
        px          : Int,
        py          : Int,
        screenWidth : Int,
        screenHeight: Int,
        sigmaPixels : Float = 3f
    ): Pair<Int, Int> {
        val (dx, dy) = gaussianPair(sigmaPixels)
        return Pair(
            (px + dx.roundToInt()).coerceIn(0, screenWidth  - 1),
            (py + dy.roundToInt()).coerceIn(0, screenHeight - 1)
        )
    }

    // ── Temporal jitter ───────────────────────────────────────────────────────

    /**
     * Return a random delay in ms drawn uniformly from [minMs, maxMs].
     * Used by MacroAccessibilityService before/after gesture dispatch.
     */
    fun timingDelayMs(
        minMs: Long = defaultMinDelayMs,
        maxMs: Long = defaultMaxDelayMs
    ): Long {
        if (minMs >= maxMs) return minMs
        return Random.nextLong(minMs, maxMs)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Box-Muller transform: generates a pair of independent N(0, σ²) samples.
     */
    private fun gaussianPair(sigma: Float): Pair<Float, Float> {
        var u1: Double
        var u2: Double
        do {
            u1 = Random.nextDouble()
        } while (u1 == 0.0)          // avoid log(0)
        u2 = Random.nextDouble()

        val r = kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1))
        val theta = 2.0 * Math.PI * u2

        val z0 = (r * kotlin.math.cos(theta) * sigma).toFloat()
        val z1 = (r * kotlin.math.sin(theta) * sigma).toFloat()
        return Pair(z0, z1)
    }
}
