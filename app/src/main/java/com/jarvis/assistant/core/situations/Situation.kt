package com.jarvis.assistant.core.situations

/**
 * Situation — a short-lived, derived read of the user's current context.
 *
 * A situation is produced by [SituationEvaluator] when several lower-level
 * signals (proactive candidates, presence, context snapshot) line up in a way
 * the rest of the system cares about.
 *
 * Situations are intentionally ephemeral — they live in the in-memory
 * [SituationRegistry] with a TTL and disappear when the context that created
 * them passes. They are *not* persisted; the persistent cousin is a [Goal],
 * which encodes a longer-running user intent.
 *
 * @param type            which situation this is (see [SituationType])
 * @param confidence      0..1 — how sure the evaluator is this holds right now
 * @param urgency         0..1 — how time-pressured the situation feels
 * @param createdAtMs     when the registry first observed this situation
 * @param expiresAtMs     when to evict the situation if not re-observed
 * @param evidence        human-readable reasons kept for trace / debug
 * @param sourceSignals   candidate trigger-ids / snapshot fields that fed it
 * @param summary         one-line description used in prompts and telemetry
 */
data class Situation(
    val type: SituationType,
    val confidence: Float,
    val urgency: Float,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val evidence: List<String>,
    val sourceSignals: List<String>,
    val summary: String,
) {
    fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs

    /** Combined ordering score used when the registry ranks simultaneous
     *  situations. Urgency outweighs confidence slightly because a certain
     *  but non-urgent situation rarely needs to be surfaced over a pressing
     *  probable one. */
    val weight: Float
        get() = (urgency * 0.6f) + (confidence * 0.4f)
}
