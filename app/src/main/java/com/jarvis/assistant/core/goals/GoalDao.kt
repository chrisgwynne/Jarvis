package com.jarvis.assistant.core.goals

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface GoalDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: GoalEntity): Long

    @Update
    suspend fun update(entity: GoalEntity)

    @Query("SELECT * FROM goals WHERE rootDedupeKey = :key LIMIT 1")
    suspend fun findByRootKey(key: String): GoalEntity?

    @Query("SELECT * FROM goals WHERE status = 'ACTIVE' AND expiresAtMs > :nowMs ORDER BY updatedAtMs DESC")
    suspend fun activeAt(nowMs: Long): List<GoalEntity>

    @Query("SELECT * FROM goals WHERE status = 'ACTIVE' AND expiresAtMs <= :nowMs")
    suspend fun overdue(nowMs: Long): List<GoalEntity>

    @Query("UPDATE goals SET status = :status, updatedAtMs = :nowMs WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, nowMs: Long)

    @Query("DELETE FROM goals WHERE status != 'ACTIVE' AND updatedAtMs < :cutoffMs")
    suspend fun pruneOlderThan(cutoffMs: Long): Int
}
