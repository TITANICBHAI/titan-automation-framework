package com.titan.automation.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Workflow DSL — JSON-serializable macro definition used by [MacroEngine].
 *
 * A Workflow is a named state machine. Each [WorkflowState] describes:
 *  - optional vision-match rule (template matching)
 *  - optional OCR scan rule (ML Kit text recognition)
 *  - an action to execute on match
 *  - success / failure transitions to next states
 *
 * States are executed in a coroutine loop until a terminal state is reached
 * (on_success = "END") or the max retries / global timeout is exhausted.
 */
@Serializable
data class WorkflowDefinition(
    @SerialName("workflow_id")           val workflowId: String,
    @SerialName("version")               val version: Int = 1,
    @SerialName("initial_state")         val initialState: String,
    @SerialName("global_timeout_ms")     val globalTimeoutMs: Long = 300_000L,
    @SerialName("rl_global_enabled")     val rlGlobalEnabled: Boolean = false,
    @SerialName("states")                val states: Map<String, WorkflowState>,
    @SerialName("actions")               val actions: Map<String, ActionDefinition>
)

@Serializable
data class WorkflowState(
    @SerialName("rl_enabled")            val rlEnabled: Boolean = false,
    @SerialName("max_retries")           val maxRetries: Int = 3,
    @SerialName("cooldown_ms")           val cooldownMs: Long = 500L,
    @SerialName("timeout_ms")            val timeoutMs: Long = 10_000L,
    @SerialName("on_success")            val onSuccess: String,
    @SerialName("on_failure")            val onFailure: String,
    @SerialName("vision_match_rule")     val visionMatchRule: VisionMatchRule? = null,
    @SerialName("ocr_scan_rule")         val ocrScanRule: OcrScanRule? = null,
    @SerialName("histogram_scan_rule")   val histogramScanRule: HistogramScanRule? = null,
    @SerialName("conditions")            val conditions: List<ConditionalBranch> = emptyList()
)

/**
 * Matching algorithm for [VisionMatchRule].
 *
 * TEMPLATE — TM_CCOEFF_NORMED with optional multi-scale resizing (default).
 *            Best for exact pixel templates; sensitive to scale/rotation.
 * ORB      — ORB keypoints + BFMatcher Hamming + Lowe ratio test.
 *            Scale/rotation-invariant; use for logos, icons, rotated elements.
 */
@Serializable
enum class MatchMode { TEMPLATE, ORB }

@Serializable
data class VisionMatchRule(
    @SerialName("template_id")           val templateId: String,
    @SerialName("min_confidence")        val minConfidence: Float = 0.85f,
    @SerialName("region")                val region: ScreenRegion? = null,
    @SerialName("multi_scale")           val multiScale: Boolean = true,
    @SerialName("match_mode")            val matchMode: MatchMode = MatchMode.TEMPLATE,
    @SerialName("action_intent")         val actionIntent: String
)

@Serializable
data class OcrScanRule(
    @SerialName("region")                val region: ScreenRegion? = null,
    @SerialName("regex_pattern")         val regexPattern: String,
    @SerialName("min_confidence")        val minConfidence: Float = 0.7f,
    @SerialName("fuzzy_match")           val fuzzyMatch: Boolean = false,
    @SerialName("action_intent")         val actionIntent: String
)

/**
 * Histogram-based state validation rule.
 *
 * Compares the 2-D HSV histogram of the current frame against a saved
 * reference screenshot ([referenceTemplateId]) using HISTCMP_CORREL.
 * Similarity ≥ [minSimilarity] → condition passes.
 *
 * Use for: scene detection (game-over screen, loading screen, main menu)
 * without requiring a pixel-perfect template match.
 */
@Serializable
data class HistogramScanRule(
    @SerialName("reference_template_id") val referenceTemplateId: String,
    @SerialName("region")                val region: ScreenRegion? = null,
    @SerialName("min_similarity")        val minSimilarity: Float = 0.70f,
    @SerialName("action_intent")         val actionIntent: String
)

@Serializable
data class ConditionalBranch(
    @SerialName("condition_type")        val conditionType: ConditionType,
    @SerialName("value")                 val value: String,
    @SerialName("action_intent")         val actionIntent: String,
    @SerialName("next_state")            val nextState: String
)

@Serializable
data class ScreenRegion(
    @SerialName("left")   val left: Int,
    @SerialName("top")    val top: Int,
    @SerialName("right")  val right: Int,
    @SerialName("bottom") val bottom: Int
)

@Serializable
data class ActionDefinition(
    @SerialName("interaction_type")  val interactionType: InteractionType,
    @SerialName("x")                 val x: Float = 0f,
    @SerialName("y")                 val y: Float = 0f,
    @SerialName("end_x")             val endX: Float = 0f,
    @SerialName("end_y")             val endY: Float = 0f,
    @SerialName("duration_ms")       val durationMs: Long = 100L,
    @SerialName("delay_after_ms")    val delayAfterMs: Long = 0L,
    @SerialName("text_input")        val textInput: String? = null
)

@Serializable
enum class InteractionType {
    TAP, LONG_PRESS, SWIPE, MULTI_TOUCH, TYPE_TEXT, WAIT
}

@Serializable
enum class ConditionType {
    OCR_CONTAINS,
    VISION_MATCH,
    ORB_MATCH,          // value = template_id — ORB feature match
    HISTOGRAM_MATCH,    // value = reference_template_id — colour histogram similarity ≥ 0.70
    STATE_FLAG,         // value = expected state name
    BATTERY_BELOW,      // value = integer percentage threshold
    THERMAL_ABOVE       // value = ThermalLevel name (LIGHT/MODERATE/SEVERE/CRITICAL)
}

/** Runtime state for a running workflow session. */
data class WorkflowSession(
    val workflowId: String,
    val currentState: String,
    val retryCount: Int = 0,
    val stepCount: Int = 0,
    val startedAtMs: Long = System.currentTimeMillis(),
    val lastCheckpointState: String = "",
    val completed: Boolean = false,
    val failed: Boolean = false
)
