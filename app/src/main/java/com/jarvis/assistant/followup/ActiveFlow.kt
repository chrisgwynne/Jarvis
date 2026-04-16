package com.jarvis.assistant.followup

import java.util.UUID

/**
 * ActiveFlow — the complete state of one multi-turn conversational task.
 *
 * Lifecycle:
 *   created → ACTIVE (collecting slots) → COMPLETED / CANCELLED / EXPIRED
 *
 * Mutation conventions:
 *   All slot operations update [updatedAt] automatically.
 *   [fillSlot] also removes the key from [missingSlots] and increments [turnCount].
 */
data class ActiveFlow(
    val id: String = UUID.randomUUID().toString(),
    val type: FlowType,
    var status: FlowStatus = FlowStatus.ACTIVE,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long,
    val sourceConversationId: String? = null,

    // ── Slot state ─────────────────────────────────────────────────────────
    /** Next slot we are specifically waiting for (set after asking a question). */
    var expectedSlot: SlotKey? = null,
    /** Slots that have already been filled. */
    val collectedSlots: MutableMap<SlotKey, SlotValue> = mutableMapOf(),
    /** Slots still needed before the flow can execute, in ask-order. */
    val missingSlots: ArrayDeque<SlotKey> = ArrayDeque(),

    // ── Context ────────────────────────────────────────────────────────────
    val recentEntities: MutableList<EntityReference> = mutableListOf(),
    var lastPrompt: String? = null,
    var confidenceScore: Float = 1.0f,
    var turnCount: Int = 0,
    var completionSummary: String? = null
) {

    // ── Slot helpers ───────────────────────────────────────────────────────

    fun slot(key: SlotKey): String? = collectedSlots[key]?.raw

    fun hasSlot(key: SlotKey): Boolean = collectedSlots.containsKey(key)

    fun fillSlot(key: SlotKey, value: String, confidence: Float = 1.0f) {
        collectedSlots[key] = SlotValue(raw = value, confidence = confidence)
        missingSlots.remove(key)
        updatedAt = System.currentTimeMillis()
        if (expectedSlot == key) expectedSlot = null
        turnCount++
    }

    fun replaceSlot(key: SlotKey, value: String, confidence: Float = 1.0f) {
        collectedSlots[key] = SlotValue(raw = value, confidence = confidence)
        updatedAt = System.currentTimeMillis()
    }

    // ── Lifecycle helpers ──────────────────────────────────────────────────

    fun isExpired(): Boolean =
        status == FlowStatus.EXPIRED ||
        (status == FlowStatus.ACTIVE && System.currentTimeMillis() > expiresAt)

    fun markCompleted(summary: String? = null) {
        status = FlowStatus.COMPLETED
        completionSummary = summary
        updatedAt = System.currentTimeMillis()
    }

    fun markCancelled() {
        status = FlowStatus.CANCELLED
        updatedAt = System.currentTimeMillis()
    }

    fun allSlotsCollected(): Boolean = missingSlots.isEmpty()
}
