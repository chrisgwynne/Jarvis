package com.jarvis.assistant.brain.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jarvis.assistant.brain.db.entity.BrainPattern

@Dao
interface BrainPatternDao {

    /** Insert or replace — patternKey has a unique index so this acts as upsert. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pattern: BrainPattern): Long

    @Update
    suspend fun update(pattern: BrainPattern)

    @Query("SELECT * FROM brain_patterns WHERE patternKey = :key LIMIT 1")
    suspend fun getByKey(key: String): BrainPattern?

    @Query("SELECT * FROM brain_patterns WHERE eventType = :type ORDER BY confidence DESC")
    suspend fun getByEventType(type: String): List<BrainPattern>

    /** All patterns with effective confidence above [minConfidence], sorted by score. */
    @Query("""
        SELECT * FROM brain_patterns
        WHERE (confidence * decayFactor) >= :minConfidence
        ORDER BY (confidence * decayFactor) DESC
    """)
    suspend fun getAboveConfidence(minConfidence: Float = 0.50f): List<BrainPattern>

    /** Patterns relevant right now — matching the given hour window and day type. */
    @Query("""
        SELECT * FROM brain_patterns
        WHERE (timeWindowStart IS NULL OR CAST(SUBSTR(timeWindowStart, 1, 2) AS INTEGER) = :hour)
          AND (dayContext IS NULL OR dayContext = 'any' OR dayContext = :dayContext)
          AND (confidence * decayFactor) >= :minConfidence
        ORDER BY (confidence * decayFactor) DESC
        LIMIT 20
    """)
    suspend fun getMatchingNow(
        hour: Int,
        dayContext: String,
        minConfidence: Float = 0.50f
    ): List<BrainPattern>

    /** Update decay factor for a pattern — called on drift detection. */
    @Query("UPDATE brain_patterns SET decayFactor = :factor, updatedAt = :now WHERE patternKey = :key")
    suspend fun updateDecay(key: String, factor: Float, now: Long = System.currentTimeMillis())

    /** Record that a suggestion was dispatched for this pattern. */
    @Query("UPDATE brain_patterns SET lastSuggestedAt = :now WHERE patternKey = :key")
    suspend fun markSuggested(key: String, now: Long = System.currentTimeMillis())

    /** Increment acceptance count when user acts on a suggestion. */
    @Query("UPDATE brain_patterns SET acceptCount = acceptCount + 1 WHERE patternKey = :key")
    suspend fun incrementAccept(key: String)

    @Query("SELECT * FROM brain_patterns ORDER BY (confidence * decayFactor) DESC")
    suspend fun getAll(): List<BrainPattern>

    @Query("DELETE FROM brain_patterns WHERE (confidence * decayFactor) < 0.10")
    suspend fun pruneWeak()
}
