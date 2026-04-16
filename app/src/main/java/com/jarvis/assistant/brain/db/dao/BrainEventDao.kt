package com.jarvis.assistant.brain.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jarvis.assistant.brain.db.entity.BrainEvent

@Dao
interface BrainEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: BrainEvent): Long

    /** All events of a given type, newest first. */
    @Query("SELECT * FROM brain_events WHERE type = :type ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByType(type: String, limit: Int = 200): List<BrainEvent>

    /** All events within a time range, for pattern analysis. */
    @Query("SELECT * FROM brain_events WHERE timestamp >= :from ORDER BY timestamp ASC")
    suspend fun getSince(from: Long): List<BrainEvent>

    /** All events in a time range for a specific type. */
    @Query("""
        SELECT * FROM brain_events
        WHERE type = :type AND timestamp >= :from
        ORDER BY timestamp ASC
    """)
    suspend fun getByTypeSince(type: String, from: Long): List<BrainEvent>

    /**
     * Events in a sliding hour window across all days — used for time-pattern detection.
     * Returns events whose hourOfDay falls within [hourFrom..hourTo].
     */
    @Query("""
        SELECT * FROM brain_events
        WHERE type = :type
          AND hourOfDay BETWEEN :hourFrom AND :hourTo
          AND timestamp >= :since
        ORDER BY timestamp ASC
    """)
    suspend fun getByTypeAndHourRange(
        type: String,
        hourFrom: Int,
        hourTo: Int,
        since: Long
    ): List<BrainEvent>

    /** Count distinct calendar days in the DB (for denominator calculations). */
    @Query("SELECT COUNT(DISTINCT (timestamp / 86400000)) FROM brain_events WHERE timestamp >= :since")
    suspend fun countDistinctDaysSince(since: Long): Int

    /** Most recent event of a given type. */
    @Query("SELECT * FROM brain_events WHERE type = :type ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestByType(type: String): BrainEvent?

    /** Delete events older than [before] — rolling 60-day window. */
    @Query("DELETE FROM brain_events WHERE timestamp < :before")
    suspend fun pruneOlderThan(before: Long)

    @Query("SELECT COUNT(*) FROM brain_events")
    suspend fun count(): Int
}
