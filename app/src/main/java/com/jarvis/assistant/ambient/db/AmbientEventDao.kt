package com.jarvis.assistant.ambient.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AmbientEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AmbientEventEntity): Long

    /** Most recent [limit] events, newest first. */
    @Query("SELECT * FROM ambient_events ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<AmbientEventEntity>

    /** Events of a specific type, newest first. */
    @Query("SELECT * FROM ambient_events WHERE type = :type ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getByType(type: String, limit: Int = 50): List<AmbientEventEntity>

    /** Events within a time window. */
    @Query("SELECT * FROM ambient_events WHERE timestampMs >= :fromMs ORDER BY timestampMs ASC")
    suspend fun getSince(fromMs: Long): List<AmbientEventEntity>

    /** Count of events for a given type since a timestamp. */
    @Query("SELECT COUNT(*) FROM ambient_events WHERE type = :type AND timestampMs >= :fromMs")
    suspend fun countByTypeSince(type: String, fromMs: Long): Int

    /** Delete events older than [beforeMs]. */
    @Query("DELETE FROM ambient_events WHERE timestampMs < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)

    /** Total event count for diagnostics. */
    @Query("SELECT COUNT(*) FROM ambient_events")
    suspend fun totalCount(): Int
}
