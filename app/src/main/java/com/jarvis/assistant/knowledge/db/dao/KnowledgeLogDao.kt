package com.jarvis.assistant.knowledge.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jarvis.assistant.knowledge.db.entity.KnowledgeLogEntry

@Dao
interface KnowledgeLogDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: KnowledgeLogEntry)

    @Query("SELECT * FROM knowledge_log ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<KnowledgeLogEntry>

    @Query("DELETE FROM knowledge_log WHERE createdAt < :cutoffMs")
    suspend fun pruneOlderThan(cutoffMs: Long): Int
}
