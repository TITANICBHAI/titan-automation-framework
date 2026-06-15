package com.titan.automation.data.repository

import com.titan.automation.data.db.MacroDatabase
import com.titan.automation.data.db.SimpleMacroEntity
import com.titan.automation.domain.model.SimpleMacro
import com.titan.automation.domain.repository.SimpleMacroRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimpleMacroRepositoryImpl @Inject constructor(
    private val db: MacroDatabase
) : SimpleMacroRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults    = true
        isLenient         = true
    }

    override fun allMacros(): Flow<List<SimpleMacro>> =
        db.simpleMacroDao().allMacros().map { entities ->
            entities.mapNotNull { entity ->
                runCatching { json.decodeFromString(SimpleMacro.serializer(), entity.jsonPayload) }
                    .getOrNull()
            }
        }

    override suspend fun getMacro(id: String): SimpleMacro? {
        val entity = db.simpleMacroDao().getById(id) ?: return null
        return runCatching { json.decodeFromString(SimpleMacro.serializer(), entity.jsonPayload) }
            .getOrNull()
    }

    override suspend fun saveMacro(macro: SimpleMacro) {
        db.simpleMacroDao().upsert(
            SimpleMacroEntity(
                id          = macro.id,
                name        = macro.name,
                jsonPayload = json.encodeToString(SimpleMacro.serializer(), macro),
                actionCount = macro.actions.size,
                createdAt   = macro.createdAt,
                updatedAt   = macro.updatedAt
            )
        )
    }

    override suspend fun deleteMacro(id: String) {
        db.simpleMacroDao().deleteById(id)
    }
}
