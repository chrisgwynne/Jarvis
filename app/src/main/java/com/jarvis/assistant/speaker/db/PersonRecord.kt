package com.jarvis.assistant.speaker.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A known person in Jarvis's household.
 *
 * Linked to voice data via [SpeakerEmbedding] rows (one per enrolled utterance).
 * The [enrollmentStatus] reflects how many voice samples have been collected and
 * drives the confidence bands used by [SpeakerEmbeddingEngine].
 *
 * [isOwner] is set for the primary device owner (seeded from the "user.name"
 * ProfileMemoryService fact on first run).
 */
@Entity(
    tableName = "person_records",
    indices = [Index("displayName")]
)
data class PersonRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    /** Stored as [EnrollmentStatus.name] string for Room compatibility. */
    val enrollmentStatus: String = EnrollmentStatus.NONE.name,
    val enrolledUtteranceCount: Int = 0,
    val lastSeenAt: Long = System.currentTimeMillis(),
    /** When true, Jarvis greets this person by name (at high confidence). */
    val greetByName: Boolean = true,
    /** True for the primary device owner. */
    val isOwner: Boolean = false
) {
    enum class EnrollmentStatus {
        /** Name known, zero voice samples collected. */
        NONE,
        /** 1–2 samples — recognition unreliable. */
        TRAINING,
        /** 3–9 samples — basic recognition. */
        SUFFICIENT,
        /** 10+ samples — reliable recognition. */
        ENROLLED
    }

    val typedEnrollmentStatus: EnrollmentStatus
        get() = try { EnrollmentStatus.valueOf(enrollmentStatus) }
                catch (_: IllegalArgumentException) { EnrollmentStatus.NONE }
}
