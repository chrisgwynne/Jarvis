package com.jarvis.assistant.memory.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jarvis.assistant.memory.db.entity.MemoryEntry
import com.jarvis.assistant.memory.db.entity.MemoryType

@Dao
interface MemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: MemoryEntry): Long

    @Update
    suspend fun update(entry: MemoryEntry)

    @Query("SELECT * FROM memory_entries ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<MemoryEntry>

    @Query("SELECT * FROM memory_entries WHERE type = :type ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getByType(type: MemoryType, limit: Int = 20): List<MemoryEntry>

    @Query("SELECT * FROM memory_entries WHERE createdAt BETWEEN :from AND :to ORDER BY createdAt DESC")
    suspend fun getByTimeRange(from: Long, to: Long): List<MemoryEntry>

    @Query("SELECT * FROM memory_entries WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getBySession(sessionId: String): List<MemoryEntry>

    @Query("""
        SELECT * FROM memory_entries
        WHERE keywords LIKE '%' || :token || '%'
        ORDER BY importanceScore DESC, createdAt DESC
        LIMIT :limit
    """)
    suspend fun searchByKeyword(token: String, limit: Int = 20): List<MemoryEntry>

    @Query("UPDATE memory_entries SET accessCount = accessCount + 1, lastAccessedAt = :now WHERE id = :id")
    suspend fun recordAccess(id: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM memory_entries WHERE createdAt < :before AND type NOT IN ('PREFERENCE', 'FACTUAL', 'ROUTINE')")
    suspend fun pruneOlderThan(before: Long)

    @Query("SELECT COUNT(*) FROM memory_entries")
    suspend fun count(): Int

    @Query("DELETE FROM memory_entries")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM memory_entries WHERE content = :content AND type = :type")
    suspend fun countByContentAndType(content: String, type: MemoryType): Int
}
