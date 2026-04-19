package com.jarvis.assistant.core.decisions

import com.jarvis.assistant.proactive.ProactiveEvent
import com.jarvis.assistant.proactive.ProactiveEventType

/**
 * Candidate — a trigger's proposal to the policy engine. Scored and either
 * dispatched or suppressed downstream.
 *
 * Carries [eventType] during the transitional period so [toProactiveEvent]
 * can feed the legacy [com.jarvis.assistant.proactive.EventScorer] /
 * [com.jarvis.assistant.proactive.DecisionEngine] unchanged while the
 * policy engine is being rebuilt on top of this shape.
 *
 * [dedupeKey] MUST be stable across ticks for the same underlying situation —
 * it's what [ActionLedger] keys cooldown and ignore-count state on.
 */
data class Candidate(
    val triggerId: String,
    val eventType: ProactiveEventType,
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
) {
    fun toProactiveEvent(): ProactiveEvent = ProactiveEvent(
        type = eventType,
        title = title,
        spokenText = spokenText,
        urgency = urgency,
        relevance = relevance,
        confidence = confidence,
        annoyanceCost = annoyanceCost,
        createdAtMillis = createdAtMs,
        dedupeKey = dedupeKey,
        metadata = metadata,
    )
}
