package com.jarvis.assistant.proactive.followup

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * PendingFollowUp — a scheduled conversational check-in.
 *
 * Created when the user shares a future event, expresses stress, or goes silent
 * for a while. Dispatched by [ConversationalProactiveEngine] when [dueAt] passes.
 */
@Entity(
    tableName = "pending_followups",
    indices = [
        Index("status"),
        Index("dueAt")
    ]
)
data class PendingFollowUp(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Category — drives timing and retry policy. */
    val type: String,

    /** Short description of the topic, used for natural-language resolution matching. */
    val topic: String,

    /** The message Jarvis will say as the check-in. */
    val promptTemplate: String,

    /** Epoch ms when this follow-up becomes eligible to fire. */
    val dueAt: Long,

    val createdAt: Long = System.currentTimeMillis(),

    val status: String = STATUS_PENDING,

    /** Last time it was dispatched (spoken/notified). */
    val lastAttemptAt: Long = 0L,

    val attemptCount: Int = 0,

    /** After this epoch ms, expire without firing. */
    val expiresAt: Long
) {
    companion object {
        const val TYPE_EVENT     = "EVENT"
        const val TYPE_WELLBEING = "WELLBEING"
        const val TYPE_GAP       = "GAP"

        const val STATUS_PENDING  = "PENDING"
        const val STATUS_SENT     = "SENT"
        const val STATUS_RESOLVED = "RESOLVED"
        const val STATUS_EXPIRED  = "EXPIRED"
        const val STATUS_IGNORED  = "IGNORED"
    }
}
