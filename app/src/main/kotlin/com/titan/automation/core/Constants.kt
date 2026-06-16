package com.titan.automation.core

/**
 * App-wide compile-time constants.
 *
 * All performance thresholds, timing budgets, and structural limits live here
 * so they can be referenced from any module without cross-module dependencies.
 */
object TitanConstants {

    // ── Frame capture ─────────────────────────────────────────────────────────

    /** Default capture loop target (frames per second). */
    const val DEFAULT_FPS: Int = 10

    /** Minimum FPS floor — enforced even under thermal/battery pressure. */
    const val MIN_FPS: Int = 3

    /** Maximum FPS ceiling — capped to limit CPU / battery impact. */
    const val MAX_FPS: Int = 15

    /** Frame difference threshold (0–1) below which a frame is considered static. */
    const val FRAME_DIFF_SKIP_THRESHOLD: Float = 0.03f

    /** BitmapPool maximum size (number of pre-allocated Bitmap slots). */
    const val BITMAP_POOL_MAX_SIZE: Int = 6

    // ── Vision / detection ────────────────────────────────────────────────────

    /** Default template matching confidence threshold (OpenCV normalised). */
    const val DEFAULT_TEMPLATE_THRESHOLD: Float = 0.85f

    /** Default OCR confidence threshold (ML Kit, 0–1). */
    const val DEFAULT_OCR_CONFIDENCE: Float = 0.75f

    /** Maximum number of template matches returned from findAllTemplates(). */
    const val MAX_TEMPLATE_MATCHES: Int = 10

    /** Multi-scale template matching scale steps (e.g. 0.8x, 1.0x, 1.2x). */
    val TEMPLATE_SCALE_STEPS: FloatArray = floatArrayOf(0.8f, 0.9f, 1.0f, 1.1f, 1.2f)

    // ── Gesture / accessibility ───────────────────────────────────────────────

    /** Gaussian jitter σ applied to every touch coordinate (pixels). */
    const val DEFAULT_JITTER_SIGMA: Float = 3.0f

    /** Maximum allowed jitter offset from target (pixels). */
    const val MAX_JITTER_OFFSET_PX: Float = 8.0f

    /** Minimum randomised delay before gesture dispatch (ms). */
    const val GESTURE_DELAY_MIN_MS: Long = 15L

    /** Maximum randomised delay before gesture dispatch (ms). */
    const val GESTURE_DELAY_MAX_MS: Long = 60L

    /** Default swipe duration (ms). */
    const val DEFAULT_SWIPE_DURATION_MS: Long = 300L

    /** Minimum long-press hold duration enforced by Android system (ms). */
    const val LONG_PRESS_MIN_DURATION_MS: Long = 500L

    /** Maximum gesture dispatch retry attempts before giving up. */
    const val GESTURE_MAX_RETRIES: Int = 3

    /** Number of Bézier waypoints per 16ms frame interval for swipe paths. */
    const val BEZIER_WAYPOINTS_PER_16MS: Int = 1

    // ── Workflow execution ────────────────────────────────────────────────────

    /** Default per-state retry limit when not specified in DSL. */
    const val DEFAULT_MAX_RETRIES: Int = 3

    /** Default per-state timeout (ms) when not specified in DSL. */
    const val DEFAULT_STATE_TIMEOUT_MS: Long = 10_000L

    /** Default cooldown between retries (ms). */
    const val DEFAULT_COOLDOWN_MS: Long = 1_000L

    /** Initial retry backoff (ms) — doubles on each subsequent retry. */
    const val RETRY_BACKOFF_BASE_MS: Long = 500L

    /** Maximum retry backoff cap (ms). */
    const val RETRY_BACKOFF_MAX_MS: Long = 8_000L

    // ── Reinforcement learning ────────────────────────────────────────────────

    /** Q-learning discount factor (γ). */
    const val RL_GAMMA: Float = 0.95f

    /** Q-learning learning rate (α). */
    const val RL_ALPHA: Float = 0.1f

    /** Initial ε-greedy exploration rate. */
    const val RL_EPSILON_START: Float = 0.3f

    /** Minimum ε after decay. */
    const val RL_EPSILON_MIN: Float = 0.05f

    /** ε decay factor applied after each episode. */
    const val RL_EPSILON_DECAY: Float = 0.995f

    /** Experience replay buffer capacity. */
    const val RL_REPLAY_BUFFER_SIZE: Int = 2_000

    /** Minimum replay buffer fill before training begins. */
    const val RL_REPLAY_MIN_FILL: Int = 64

    /** Mini-batch size for DQN-lite training step. */
    const val RL_BATCH_SIZE: Int = 32

    /** Step-penalty coefficient ψ in reward = R_base − ψ × N_steps. */
    const val RL_STEP_PENALTY: Float = 0.05f

    // ── Thermal / battery governance ─────────────────────────────────────────

    /** Battery % below which RL training is suspended. */
    const val BATTERY_DISABLE_RL_THRESHOLD: Int = 30

    /** Battery % below which FPS is reduced to minimum. */
    const val BATTERY_MIN_FPS_THRESHOLD: Int = 20

    /** Battery % below which inference + OCR are disabled. */
    const val BATTERY_DISABLE_INFERENCE_THRESHOLD: Int = 15

    /** Battery % below which the macro is paused. */
    const val BATTERY_PAUSE_THRESHOLD: Int = 10

    // ── Telemetry ─────────────────────────────────────────────────────────────

    /** Telemetry ring buffer depth (entries kept in RAM). */
    const val TELEMETRY_RING_SIZE: Int = 200

    /** Batch insert interval for Room telemetry writes (ms). */
    const val TELEMETRY_BATCH_INTERVAL_MS: Long = 100L

    /** Days after which telemetry log entries are pruned from Room. */
    const val TELEMETRY_RETENTION_DAYS: Int = 7

    // ── Watchdog ──────────────────────────────────────────────────────────────

    /** MacroEngine heartbeat emission interval (ms). */
    const val WATCHDOG_HEARTBEAT_INTERVAL_MS: Long = 5_000L

    /** Watchdog timeout window — restart triggered if no heartbeat received (ms). */
    const val WATCHDOG_TIMEOUT_MS: Long = 10_000L

    /** Consecutive watchdog failures before WatchdogGaveUp is emitted. */
    const val WATCHDOG_MAX_FAILURES: Int = 3

    /** Maximum active coroutines in engine scope before leak alert. */
    const val WATCHDOG_MAX_COROUTINES: Int = 50

    // ── Hot reload ────────────────────────────────────────────────────────────

    /** Hot-reload polling interval for workflow directory changes (ms). */
    const val HOT_RELOAD_POLL_INTERVAL_MS: Long = 2_000L
}
