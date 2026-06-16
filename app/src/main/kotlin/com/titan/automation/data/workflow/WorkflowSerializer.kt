package com.titan.automation.data.workflow

import com.titan.automation.domain.model.WorkflowDefinition
import com.titan.automation.engine.workflow.WorkflowParser
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkflowSerializer — domain model → JSON codec.
 *
 * Delegates to [WorkflowParser] which owns the kotlinx.serialization-backed
 * codec for [WorkflowDefinition]. This class exists as a named DI entry-point
 * so callers can request either compact or pretty-printed output without
 * importing [WorkflowParser] directly.
 *
 * Usage:
 *   @Inject lateinit var serializer: WorkflowSerializer
 *   val compact     = serializer.toJson(workflow)
 *   val readable    = serializer.toPrettyJson(workflow)
 *   val workflow    = serializer.fromJson(jsonString)
 */
@Singleton
class WorkflowSerializer @Inject constructor(
    private val parser: WorkflowParser
) {

    /**
     * Serialises [workflow] to a compact JSON string.
     * Throws [kotlinx.serialization.SerializationException] on encoding failure.
     */
    fun toJson(workflow: WorkflowDefinition): String = parser.serialize(workflow)

    /**
     * Serialises [workflow] to a human-readable pretty-printed JSON string.
     */
    fun toPrettyJson(workflow: WorkflowDefinition): String = parser.prettyPrint(workflow)

    /**
     * Parses a [WorkflowDefinition] from [jsonText].
     * Returns null if [jsonText] is malformed rather than throwing.
     */
    fun fromJson(jsonText: String): WorkflowDefinition? =
        runCatching { parser.parse(jsonText) }.getOrNull()

    /**
     * Validates [jsonText] without full deserialisation.
     * Returns null if valid, or an error message string if not.
     */
    fun validate(jsonText: String): String? = parser.validate(jsonText)
}
