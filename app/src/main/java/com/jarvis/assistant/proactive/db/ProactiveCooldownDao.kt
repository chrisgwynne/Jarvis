package com.jarvis.assistant.proactive.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO for [ProactiveCooldownEntity].  All operations are suspend so callers
 * never block a dispatcher thread.
 */
@Dao
interface ProactiveCooldownDao {

    /** Return every stored entry so [CooldownStore] can rehydrate its cache on init. */
    @Query("SELECT * FROM proactive_cooldowns")
    suspend fun getAll(): List<ProactiveCooldownEntity>

    /** Insert or overwrite a single entry. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ProactiveCooldownEntity)

    @Query("DELETE FROM proactive_cooldowns WHERE dedupeKey = :key")
    suspend fun delete(key: String)

    /** Drop entries whose [ProactiveCooldownEntity.lastSurfacedMs] is older than [cutoffMs]. */
    @Query("DELETE FROM proactive_cooldowns WHERE lastSurfacedMs < :cutoffMs AND dedupeKey != '__global__'")
    suspend fun deleteExpired(cutoffMs: Long)
}
