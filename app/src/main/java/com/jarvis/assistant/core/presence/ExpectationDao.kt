package com.jarvis.assistant.core.presence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ExpectationDao {
    @Insert
    suspend fun insert(entity: ExpectationEntity): Long

    @Query("SELECT * FROM expectations WHERE status = 'PENDING' AND expiresAtMs > :nowMs ORDER BY createdAtMs DESC")
    suspend fun pending(nowMs: Long): List<ExpectationEntity>

    @Query("SELECT * FROM expectations WHERE status = 'PENDING' AND triggerEventKind = :kind AND expiresAtMs > :nowMs")
    suspend fun pendingForEvent(kind: String, nowMs: Long): List<ExpectationEntity>

    @Query("SELECT * FROM expectations WHERE status = 'PENDING' AND triggerAtMs IS NOT NULL AND triggerAtMs <= :nowMs AND expiresAtMs > :nowMs")
    suspend fun dueByTime(nowMs: Long): List<ExpectationEntity>

    @Query("UPDATE expectations SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("UPDATE expectations SET status = 'EXPIRED' WHERE status = 'PENDING' AND expiresAtMs <= :nowMs")
    suspend fun expireOverdue(nowMs: Long): Int

    @Query("DELETE FROM expectations WHERE status != 'PENDING' AND createdAtMs < :cutoffMs")
    suspend fun pruneOlderThan(cutoffMs: Long): Int
}
