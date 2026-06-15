package com.titan.automation.domain.repository

import com.titan.automation.domain.model.SimpleMacro
import kotlinx.coroutines.flow.Flow

/**
 * SimpleMacroRepository — persistence boundary for user-created simple macros.
 */
interface SimpleMacroRepository {

    /** Emit all stored simple macros as a cold Flow, ordered newest-first. */
    fun allMacros(): Flow<List<SimpleMacro>>

    /** Load a single macro by ID, or null if not found. */
    suspend fun getMacro(id: String): SimpleMacro?

    /** Insert or replace a macro. */
    suspend fun saveMacro(macro: SimpleMacro)

    /** Delete a macro by ID. */
    suspend fun deleteMacro(id: String)
}
