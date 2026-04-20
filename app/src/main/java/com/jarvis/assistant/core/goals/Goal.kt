package com.jarvis.assistant.core.goals

import com.jarvis.assistant.core.situations.SituationType

/**
 * Goal — a persisted, longer-running user intent.
 *
 * Lifecycle:
 *
 *   ACTIVE -> COMPLETED   (plan succeeded / situation resolved)
 *   ACTIVE -> ABANDONED   (user told us to stop, or TTL elapsed)
 *   ACTIVE -> FAILED      (underlying plan halted and couldn't recover)
 *
 * Goals are allowed to overlap. The [GoalStore] is responsible for
 * idempotency — re-upserting the same (type, rootDedupeKey) pair doesn't
 * create duplicates.
 *
 * @param id              Room row id; 0 before insert.
 * @param type            broad category; see [GoalType]
 * @param title           short human-readable label for prompts / logs
 * @param status          lifecycle, one of [STATUS_ACTIVE], [STATUS_COMPLETED],
 *                        [STATUS_ABANDONED], [STATUS_FAILED]
 * @param originSituation situation that spawned the goal, if any
 * @param rootDedupeKey   key to collapse re-triggers (e.g. same meeting id)
 * @param createdAtMs     wall-clock when first observed
 * @param updatedAtMs     wall-clock of last upsert
 * @param expiresAtMs     wall-clock when the goal auto-abandons if still active
 * @param evidence        short reasoning lines (for trace / debug)
 */
data class Goal(
    val id: Long = 0,
    val type: GoalType,
    val title: String,
    val status: String = STATUS_ACTIVE,
    val originSituation: SituationType? = null,
    val rootDedupeKey: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val expiresAtMs: Long,
    val evidence: List<String> = emptyList(),
) {
    fun isActive(nowMs: Long): Boolean =
        status == STATUS_ACTIVE && nowMs < expiresAtMs

    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_ABANDONED = "ABANDONED"
        const val STATUS_FAILED = "FAILED"
    }
}
