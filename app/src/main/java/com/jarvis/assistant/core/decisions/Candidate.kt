package com.jarvis.assistant.core.decisions

/**
 * Candidate — a trigger's proposal to the policy engine. Scored and either
 * dispatched or suppressed downstream.
 *
 * Parallel in shape to the legacy [com.jarvis.assistant.proactive.ProactiveEvent]
 * so triggers can be ported one at a time without breaking callers. Once the
 * porting is done, ProactiveEvent collapses into this.
 *
 * [dedupeKey] MUST be stable across ticks for the same underlying situation —
 * it's what [com.jarvis.assistant.core.decisions.ActionLedger] keys cooldown
 * and ignore-count state on.
 */
data class Candidate(
    val triggerId: String,
    val title: String,
    val spokenText: String?,
    val urgency: Float,
    val relevance: Float,
    val confidence: Float,
    val annoyanceCost: Float,
    val dedupeKey: String,
    val actionClass: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val createdAtMs: Long = System.currentTimeMillis(),
)
