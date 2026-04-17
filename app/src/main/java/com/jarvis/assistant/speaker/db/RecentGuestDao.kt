package com.jarvis.assistant.speaker.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RecentGuestDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(guest: RecentGuest): Long

    @Query("SELECT * FROM recent_guests WHERE lastSeenAt > :since ORDER BY lastSeenAt DESC")
    suspend fun getRecentSince(since: Long): List<RecentGuest>

    @Query("SELECT * FROM recent_guests WHERE displayNameNormalized = :normalized LIMIT 1")
    suspend fun getByNormalizedName(normalized: String): RecentGuest?

    @Query("UPDATE recent_guests SET lastSeenAt = :timestamp WHERE displayNameNormalized = :normalized")
    suspend fun updateLastSeen(normalized: String, timestamp: Long)

    @Query("DELETE FROM recent_guests WHERE lastSeenAt < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)
}
