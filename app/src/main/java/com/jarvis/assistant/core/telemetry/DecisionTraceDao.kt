package com.jarvis.assistant.core.telemetry

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface DecisionTraceDao {
    @Insert
    suspend fun insert(entity: DecisionTraceEntity): Long

    @Query("SELECT * FROM decision_traces ORDER BY createdAtMs DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<DecisionTraceEntity>

    @Query("SELECT * FROM decision_traces WHERE outcome = :outcome ORDER BY createdAtMs DESC LIMIT :limit")
    suspend fun latestByOutcome(outcome: String, limit: Int): List<DecisionTraceEntity>

    @Query("DELETE FROM decision_traces WHERE createdAtMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    @Query("SELECT COUNT(*) FROM decision_traces")
    suspend fun count(): Int
}
