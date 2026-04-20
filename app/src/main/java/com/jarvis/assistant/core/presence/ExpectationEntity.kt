package com.jarvis.assistant.core.presence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ExpectationEntity — short-term anticipation the agent is holding.
 * "Dan said he'll call back at 6", "usually charges around 22:00".
 *
 * [triggerAtMs] is populated for time-based expectations so the
 * proactive engine can surface them when the moment arrives.
 * [triggerEventKind] is populated for event-based expectations so
 * an arriving event of that kind resolves and possibly upgrades
 * urgency of a related signal.
 *
 * One of the two MAY be null; both may be null if the expectation
 * is purely contextual (informs the system prompt without being
 * checked against a specific trigger).
 */
@Entity(
    tableName = "expectations",
    indices = [
        Index("triggerAtMs"),
        Index("triggerEventKind"),
        Index("status"),
    ],
)
data class ExpectationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val triggerAtMs: Long? = null,
    val triggerEventKind: String? = null,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val status: String = STATUS_PENDING,
    val sourceTranscript: String? = null,
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_FULFILLED = "FULFILLED"
        const val STATUS_EXPIRED = "EXPIRED"
    }
}
