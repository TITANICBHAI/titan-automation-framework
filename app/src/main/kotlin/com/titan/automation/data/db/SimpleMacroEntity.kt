package com.titan.automation.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ── Entity ────────────────────────────────────────────────────────────────────

@Entity(tableName = "simple_macros")
data class SimpleMacroEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val jsonPayload: String,              // Full SimpleMacro serialised to JSON
    val actionCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long = 0L,
    val runCount: Int = 0
)

// ── DAO ───────────────────────────────────────────────────────────────────────

@Dao
interface SimpleMacroDao {

    @Query("SELECT * FROM simple_macros ORDER BY updatedAt DESC")
    fun allMacros(): Flow<List<SimpleMacroEntity>>

    @Query("SELECT * FROM simple_macros WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SimpleMacroEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SimpleMacroEntity)

    @Query("DELETE FROM simple_macros WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE simple_macros SET runCount = runCount + 1, lastRunAt = :ts WHERE id = :id")
    suspend fun incrementRunCount(id: String, ts: Long = System.currentTimeMillis())
}
