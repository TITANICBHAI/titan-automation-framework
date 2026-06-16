package com.titan.automation.data.workflow

import com.titan.automation.domain.model.WorkflowDefinition
import com.titan.automation.domain.model.WorkflowState
import com.titan.automation.domain.model.ActionSpec
import com.titan.automation.domain.model.VisionMatchRule
import com.titan.automation.domain.model.OcrScanRule
import com.titan.automation.domain.model.RegionBounds
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkflowSerializer — bidirectional JSON ↔ [WorkflowDefinition] codec.
 *
 * Serialises domain models to the canonical TITAN workflow JSON schema.
 * This is the inverse of [WorkflowParser] and must remain in sync with it.
 *
 * Uses [kotlinx.serialization] for safe, reflectionless encoding.
 * All nullable fields are omitted from output (not written as JSON null).
 */
@Singleton
class WorkflowSerializer @Inject constructor() {

    private val json = Json {
        prettyPrint      = true
        encodeDefaults   = false
        ignoreUnknownKeys = true
    }

    /**
     * Serialises [workflow] to a pretty-printed JSON string.
     * Throws [SerializationException] if a required field cannot be encoded.
     */
    fun serialize(workflow: WorkflowDefinition): String {
        val root = buildJsonObject {
            put("workflow_id",             workflow.workflowId)
            put("name",                    workflow.name)
            put("version",                 workflow.version)
            put("description",             workflow.description)
            put("initial_execution_state", workflow.initialState)
            put("global_timeout_ms",       workflow.globalTimeoutMs)
            put("max_global_retries",      workflow.maxGlobalRetries)

            if (workflow.states.isNotEmpty()) {
                put("states", JsonObject(
                    workflow.states.mapValues { (_, state) -> serializeState(state) }
                ))
            }

            if (workflow.actions.isNotEmpty()) {
                put("actions", JsonObject(
                    workflow.actions.mapValues { (_, action) -> serializeAction(action) }
                ))
            }
        }
        return root.toString()
    }

    private fun serializeState(state: WorkflowState): JsonElement = buildJsonObject {
        put("rl_enabled",         state.rlEnabled)
        put("max_retries",        state.maxRetries)
        put("timeout_ms",         state.timeoutMs)
        put("cooldown_duration_ms", state.cooldownMs)
        put("on_success",         state.onSuccess)
        put("on_failure",         state.onFailure)

        state.visionMatchRule?.let { rule ->
            put("vision_match_rule", buildJsonObject {
                put("template_id",        rule.templateId)
                put("minimum_threshold",  rule.minimumThreshold)
                put("action_intent",      rule.actionIntent)
                rule.regionBounds?.let { b ->
                    put("region_bounds", buildJsonObject {
                        put("left",   b.left)
                        put("top",    b.top)
                        put("right",  b.right)
                        put("bottom", b.bottom)
                    })
                }
            })
        }

        state.ocrScanRule?.let { rule ->
            put("ocr_scan_rule", buildJsonObject {
                put("expected_regex_pattern", rule.expectedRegexPattern)
                put("action_intent",          rule.actionIntent)
                put("confidence_threshold",   rule.confidenceThreshold)
                rule.regionBounds?.let { b ->
                    put("region_bounds", buildJsonObject {
                        put("left",   b.left)
                        put("top",    b.top)
                        put("right",  b.right)
                        put("bottom", b.bottom)
                    })
                }
            })
        }

        if (state.subSteps.isNotEmpty()) {
            put("sub_steps", JsonArray(state.subSteps.map { JsonPrimitive(it) }))
        }
    }

    private fun serializeAction(action: ActionSpec): JsonElement = buildJsonObject {
        put("interaction_type", action.interactionType)
        action.x?.let { put("x", it) }
        action.y?.let { put("y", it) }
        action.xRatio?.let { put("x_ratio", it) }
        action.yRatio?.let { put("y_ratio", it) }
        action.endXRatio?.let { put("end_x_ratio", it) }
        action.endYRatio?.let { put("end_y_ratio", it) }
        action.durationMs?.let { put("duration_ms", it) }
        action.text?.let { put("text", it) }
    }

    // ── DSL helpers ───────────────────────────────────────────────────────────

    private fun buildJsonObject(block: MutableMap<String, JsonElement>.() -> Unit): JsonObject {
        val map = mutableMapOf<String, JsonElement>()
        map.block()
        return JsonObject(map)
    }

    private fun MutableMap<String, JsonElement>.put(key: String, value: String?) {
        if (value != null) this[key] = JsonPrimitive(value)
    }

    private fun MutableMap<String, JsonElement>.put(key: String, value: Int) {
        this[key] = JsonPrimitive(value)
    }

    private fun MutableMap<String, JsonElement>.put(key: String, value: Long) {
        this[key] = JsonPrimitive(value)
    }

    private fun MutableMap<String, JsonElement>.put(key: String, value: Float) {
        this[key] = JsonPrimitive(value)
    }

    private fun MutableMap<String, JsonElement>.put(key: String, value: Double) {
        this[key] = JsonPrimitive(value)
    }

    private fun MutableMap<String, JsonElement>.put(key: String, value: Boolean) {
        this[key] = JsonPrimitive(value)
    }

    private fun MutableMap<String, JsonElement>.put(key: String, value: JsonElement) {
        this[key] = value
    }
}
