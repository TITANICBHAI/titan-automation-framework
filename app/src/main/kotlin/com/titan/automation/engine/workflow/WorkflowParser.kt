package com.titan.automation.engine.workflow

import com.titan.automation.domain.model.WorkflowDefinition
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkflowParser — JSON ↔ WorkflowDefinition serialization layer.
 *
 * Uses Kotlinx Serialization with lenient parsing to support:
 *   - Forward-compatible workflow files (unknown keys ignored)
 *   - Hot-reload from assets / user-imported JSON files
 *   - Export for backup and cross-device sharing
 */
@Singleton
class WorkflowParser @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys     = true
        isLenient             = true
        prettyPrint           = false
        encodeDefaults        = true
        coerceInputValues     = true
    }

    private val prettyJson = Json {
        ignoreUnknownKeys = true
        isLenient         = true
        prettyPrint       = true
        encodeDefaults    = true
    }

    /**
     * Parse a [WorkflowDefinition] from a JSON string.
     * Throws [kotlinx.serialization.SerializationException] on malformed input.
     */
    fun parse(jsonText: String): WorkflowDefinition =
        json.decodeFromString(WorkflowDefinition.serializer(), jsonText)

    /**
     * Serialize a [WorkflowDefinition] to a compact JSON string.
     */
    fun serialize(workflow: WorkflowDefinition): String =
        json.encodeToString(WorkflowDefinition.serializer(), workflow)

    /**
     * Serialize a [WorkflowDefinition] to a human-readable pretty-printed JSON string.
     */
    fun prettyPrint(workflow: WorkflowDefinition): String =
        prettyJson.encodeToString(WorkflowDefinition.serializer(), workflow)

    /**
     * Validate a raw JSON string without fully deserialising.
     * Returns null if valid, or an error message if not.
     */
    fun validate(jsonText: String): String? = runCatching {
        json.decodeFromString(WorkflowDefinition.serializer(), jsonText)
        null
    }.getOrElse { it.message ?: "Unknown parse error" }
}
