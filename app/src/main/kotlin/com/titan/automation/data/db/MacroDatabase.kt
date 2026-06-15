package com.titan.automation.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ── Entity ────────────────────────────────────────────────────────────────────

@Entity(tableName = "workflows")
data class WorkflowEntity(
    @PrimaryKey
    val workflowId: String,
    val version: Int,
    val jsonPayload: String,          // Full WorkflowDefinition serialised to JSON
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long = 0L,
    val runCount: Int = 0,
    val successCount: Int = 0
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val workflowId: String,
    val currentState: String,
    val lastCheckpointState: String,
    val stepCount: Int,
    val retryCount: Int,
    val startedAt: Long,
    val savedAt: Long = System.currentTimeMillis(),
    val completed: Boolean,
    val failed: Boolean
)

@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey
    val templateId: String,
    val label: String,
    val bitmapBytes: ByteArray,       // PNG-encoded bitmap
    val width: Int,
    val height: Int,
    val pluginId: String = "",
    val addedAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TemplateEntity) return false
        return templateId == other.templateId
    }
    override fun hashCode() = templateId.hashCode()
}

// ── DAOs ──────────────────────────────────────────────────────────────────────

@Dao
interface WorkflowDao {

    @Query("SELECT * FROM workflows ORDER BY updatedAt DESC")
    fun allWorkflows(): Flow<List<WorkflowEntity>>

    @Query("SELECT * FROM workflows WHERE workflowId = :id LIMIT 1")
    suspend fun getById(id: String): WorkflowEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WorkflowEntity)

    @Query("DELETE FROM workflows WHERE workflowId = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE workflows SET runCount = runCount + 1, lastRunAt = :ts WHERE workflowId = :id")
    suspend fun incrementRunCount(id: String, ts: Long = System.currentTimeMillis())

    @Query("UPDATE workflows SET successCount = successCount + 1 WHERE workflowId = :id")
    suspend fun incrementSuccessCount(id: String)
}

@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SessionEntity): Long

    @Query("SELECT * FROM sessions WHERE completed = 0 AND failed = 0 ORDER BY savedAt DESC LIMIT 1")
    suspend fun getActiveSession(): SessionEntity?

    @Query("DELETE FROM sessions WHERE completed = 1 OR failed = 1")
    suspend fun clearFinished()

    @Query("DELETE FROM sessions")
    suspend fun clearAll()
}

@Dao
interface TemplateDao {

    @Query("SELECT * FROM templates WHERE templateId = :id LIMIT 1")
    suspend fun getById(id: String): TemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TemplateEntity)

    @Query("DELETE FROM templates WHERE templateId = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM templates WHERE pluginId = :pluginId")
    suspend fun getByPlugin(pluginId: String): List<TemplateEntity>
}

// ── Database ──────────────────────────────────────────────────────────────────

@Database(
    entities   = [WorkflowEntity::class, SessionEntity::class, TemplateEntity::class],
    version    = 1,
    exportSchema = true
)
abstract class MacroDatabase : RoomDatabase() {
    abstract fun workflowDao(): WorkflowDao
    abstract fun sessionDao(): SessionDao
    abstract fun templateDao(): TemplateDao
}
