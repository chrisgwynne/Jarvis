package com.jarvis.assistant.speaker.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A person who has introduced themselves by name in a past session but has not
 * enrolled a voice profile.  Used to surface "Are you Emma?" style prompts
 * when an unknown voice is heard, avoiding a cold "Who's this?" every time.
 *
 * Pruned automatically after [MAX_AGE_MS] (30 days) to avoid stale suggestions.
 */
@Entity(
    tableName = "recent_guests",
    indices = [Index("displayNameNormalized"), Index("lastSeenAt")]
)
data class RecentGuest(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    /** Lowercase trimmed name — used for deduplication. */
    val displayNameNormalized: String,
    val lastSeenAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000  // 30 days
    }
}
