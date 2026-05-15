package com.jarvis.assistant.session

import java.util.UUID

/** The broad intent category Jarvis is working towards. */
enum class GoalType {
    SEND_MESSAGE,
    CREATE_TODOIST_TASK,
    CREATE_CALENDAR_EVENT,
    EDIT_CALENDAR_EVENT,
    START_NAVIGATION,
    SHARE_MEDIA,
    REPLY_TO_MESSAGE,
    EDIT_RECENT_TASK,
    ANALYSE_IMAGE,
    READ_NOTIFICATION,
    HA_CONTROL,
    GENERAL,
}

/** Lifecycle state of a [ConversationGoal]. */
enum class GoalStatus {
    ACTIVE,
    AWAITING_SLOT,
    READY_TO_EXECUTE,
    EXECUTED,
    CANCELLED,
    EXPIRED,
}

/**
 * A named slot that must be filled before a goal can execute.
 *
 * @param name     machine name, e.g. "recipient", "body", "time"
 * @param prompt   what Jarvis asks when the slot is missing
 * @param filled   the value once collected; null when still pending
 */
data class PendingSlot(
    val name: String,
    val prompt: String,
    var filled: String? = null,
) {
    val isFilled: Boolean get() = filled != null
}

/**
 * A multi-step conversation goal with slot tracking.
 *
 * Goals are short-lived (30 s–5 min depending on type) and live only in the
 * [SessionStateEngine].  They are NOT persisted across process restarts — the
 * longer-arc goal tracking lives in [com.jarvis.assistant.core.goals.GoalStore].
 */
data class ConversationGoal(
    val id: String = UUID.randomUUID().toString(),
    val type: GoalType,
    var status: GoalStatus = GoalStatus.ACTIVE,
    val slots: List<PendingSlot> = emptyList(),
    /** Arbitrary metadata the creating tool wants preserved. */
    val meta: MutableMap<String, String> = mutableMapOf(),
    val sourceIntent: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    var expiresAt: Long = System.currentTimeMillis() + DEFAULT_TTL_MS,
    var confidence: Float = 1.0f,
) {
    companion object {
        const val DEFAULT_TTL_MS = 60_000L   // 60 s while awaiting a slot
        const val EXECUTED_TTL_MS = 30_000L  // 30 s after execution (context still live)
    }

    val nextUnfilledSlot: PendingSlot?
        get() = slots.firstOrNull { !it.isFilled }

    val allSlotsFilled: Boolean
        get() = slots.all { it.isFilled }

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs > expiresAt

    /** Convenience — read a slot value by name. */
    fun slot(name: String): String? = slots.firstOrNull { it.name == name }?.filled

    /** Fill the first matching unfilled slot. Returns true if successful. */
    fun fillSlot(name: String, value: String): Boolean {
        val slot = slots.firstOrNull { it.name == name && !it.isFilled } ?: return false
        slot.filled = value
        return true
    }

    /** Fill by position (used by PendingSlotResolver when only one slot is pending). */
    fun fillNext(value: String): Boolean {
        val slot = nextUnfilledSlot ?: return false
        slot.filled = value
        return true
    }
}

/**
 * A deferred tool execution waiting for a confirmation or one more piece of info.
 *
 * Simpler than a full [ConversationGoal] — used for single-shot risky actions
 * that need the user to say "yes" before execution.
 */
data class PendingAction(
    val toolName: String,
    val params: Map<String, String>,
    val pendingSlot: String = "",
    val prompt: String,
    val expiresAt: Long = System.currentTimeMillis() + 20_000L,
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs > expiresAt
}
