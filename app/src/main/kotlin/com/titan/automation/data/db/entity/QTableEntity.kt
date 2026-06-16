package com.titan.automation.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.nio.ByteBuffer

/**
 * QTableEntity — Room entity for persisting Q-table entries.
 *
 * FloatArray is stored as a BLOB via [FloatArrayConverter].
 * Entries are keyed by [stateHash] (SHA-256 truncated to 16 bytes / 32 hex chars).
 *
 * Persistence strategy (from spec §7.4):
 *   - [QTable] snapshots every 100 updates via WorkflowCheckpoint / WorkflowDataStore
 *   - Batch-upsert to Room to avoid per-update I/O
 *   - Max 10,000 entries (enforced by QTable's LRU — DB reflects current table)
 */
@Entity(tableName = "q_table_entries")
@TypeConverters(FloatArrayConverter::class)
data class QTableEntity(
    @PrimaryKey val stateHash  : String,
    val qValues    : FloatArray,
    val updateCount: Int,
    val lastUpdated: Long
) {
    // FloatArray equality must be structural
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QTableEntity) return false
        return stateHash == other.stateHash &&
               updateCount == other.updateCount &&
               qValues.contentEquals(other.qValues)
    }
    override fun hashCode(): Int = stateHash.hashCode()
}

/**
 * TypeConverter for FloatArray ↔ ByteArray (BLOB) for Room storage.
 */
class FloatArrayConverter {
    @TypeConverter
    fun fromFloatArray(value: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(value.size * Float.SIZE_BYTES)
        value.forEach { buf.putFloat(it) }
        return buf.array()
    }

    @TypeConverter
    fun toFloatArray(bytes: ByteArray): FloatArray {
        val buf    = ByteBuffer.wrap(bytes)
        val result = FloatArray(bytes.size / Float.SIZE_BYTES)
        for (i in result.indices) result[i] = buf.float
        return result
    }
}
