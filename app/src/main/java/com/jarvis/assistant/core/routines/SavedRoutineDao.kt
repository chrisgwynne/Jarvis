package com.jarvis.assistant.core.routines

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SavedRoutineDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(routine: SavedRoutineEntity): Long

    @Query("SELECT * FROM saved_routines ORDER BY createdAtMs DESC")
    suspend fun listAll(): List<SavedRoutineEntity>

    @Query("SELECT * FROM saved_routines WHERE nameNormalized = :name LIMIT 1")
    suspend fun findByName(name: String): SavedRoutineEntity?

    @Query("DELETE FROM saved_routines WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM saved_routines WHERE nameNormalized = :name")
    suspend fun deleteByName(name: String): Int

    @Query("UPDATE saved_routines SET lastRunAtMs = :ts, runCount = runCount + 1 WHERE id = :id")
    suspend fun markRun(id: Long, ts: Long)
}
