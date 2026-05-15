package com.jarvis.assistant.ambient.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface RoutinePatternDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RoutinePatternEntity): Long

    @Update
    suspend fun update(entity: RoutinePatternEntity)

    @Query("SELECT * FROM routine_patterns ORDER BY confidence DESC")
    suspend fun getAll(): List<RoutinePatternEntity>

    @Query("SELECT * FROM routine_patterns WHERE triggerType = :type ORDER BY confidence DESC LIMIT 1")
    suspend fun getBestForType(type: String): RoutinePatternEntity?

    /** Patterns confident enough to emit proactive nudges. */
    @Query(
        "SELECT * FROM routine_patterns WHERE confidence >= :threshold AND seenCount >= :minSeen " +
        "ORDER BY confidence DESC"
    )
    suspend fun getConfident(threshold: Float, minSeen: Int): List<RoutinePatternEntity>

    @Query("UPDATE routine_patterns SET dismissedCount = dismissedCount + 1, " +
           "confidence = MAX(0.0, confidence - 0.05) WHERE id = :id")
    suspend fun recordDismissal(id: Long)

    @Query("UPDATE routine_patterns SET confidence = MIN(1.0, confidence + 0.05) WHERE id = :id")
    suspend fun recordAccept(id: Long)

    @Query("DELETE FROM routine_patterns")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM routine_patterns")
    suspend fun totalCount(): Int
}
