package com.jarvis.assistant.knowledge.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jarvis.assistant.knowledge.db.entity.KnowledgeSource

@Dao
interface KnowledgeSourceDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(source: KnowledgeSource): Long

    @Query("UPDATE knowledge_sources SET compiledAt = :compiledAt WHERE id = :id")
    suspend fun markCompiled(id: Long, compiledAt: Long)

    @Query("SELECT * FROM knowledge_sources WHERE compiledAt IS NULL ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getUncompiled(limit: Int = 20): List<KnowledgeSource>

    @Query("SELECT * FROM knowledge_sources WHERE retentionClass = :retentionClass")
    suspend fun getByRetentionClass(retentionClass: String): List<KnowledgeSource>

    @Query("""
        DELETE FROM knowledge_sources
        WHERE retentionClass = :retentionClass
        AND createdAt < :cutoffMs
        AND compiledAt IS NOT NULL
    """)
    suspend fun pruneOlderThan(cutoffMs: Long, retentionClass: String): Int

    @Query("SELECT COUNT(*) FROM knowledge_sources WHERE compiledAt IS NULL")
    suspend fun countUncompiled(): Int
}
