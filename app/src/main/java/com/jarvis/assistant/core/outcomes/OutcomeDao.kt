package com.jarvis.assistant.core.outcomes

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface OutcomeDao {
    @Insert
    suspend fun insert(entity: OutcomeEntity): Long

    @Query("SELECT * FROM outcomes WHERE occurredAtMs >= :sinceMs ORDER BY occurredAtMs DESC LIMIT :limit")
    suspend fun recent(sinceMs: Long, limit: Int = 200): List<OutcomeEntity>

    @Query("SELECT * FROM outcomes WHERE actionClass = :actionClass AND occurredAtMs >= :sinceMs ORDER BY occurredAtMs DESC")
    suspend fun recentForClass(actionClass: String, sinceMs: Long): List<OutcomeEntity>

    @Query("SELECT * FROM outcomes WHERE goalId = :goalId ORDER BY occurredAtMs ASC")
    suspend fun forGoal(goalId: Long): List<OutcomeEntity>

    @Query("SELECT COUNT(*) FROM outcomes WHERE actionClass = :actionClass AND type = :type AND occurredAtMs >= :sinceMs")
    suspend fun countOfType(actionClass: String, type: String, sinceMs: Long): Int

    @Query("DELETE FROM outcomes WHERE occurredAtMs < :cutoffMs")
    suspend fun pruneOlderThan(cutoffMs: Long): Int
}
