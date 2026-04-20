package com.jarvis.assistant.core.outcomes

import com.jarvis.assistant.core.situations.SituationType

/**
 * Outcome — the factual record of what happened after a decision was made.
 *
 * Every surfaced proactive event, every executed tool call, every plan
 * eventually resolves to an outcome. Outcomes are the feedback the rest of
 * the system needs to learn: the scorer lowers confidence after repeated
 * IGNOREs, memory tags topics the user consistently corrects, goals mark
 * themselves complete when the downstream plan succeeds.
 *
 * Outcomes are persistent (see [OutcomeEntity]). In-memory consumers that
 * only care about the present tick read them back through [OutcomeRecorder].
 *
 * @param type           coarse category; see [OutcomeType]
 * @param actionClass    canonical class (e.g. "BATTERY", "CALL", "CALENDAR")
 *                       used to fan the outcome out to the ledger/cooldown
 * @param dedupeKey      proactive dedupe key, or a synthetic tool-execution
 *                       key, so downstream sinks can coalesce
 * @param situationType  top situation at the moment of decision, if any
 * @param goalId         id of the goal this outcome advanced, if any
 * @param planId         id of the plan this outcome advanced, if any
 * @param traceId        id of the DecisionTrace row, if emitted
 * @param occurredAtMs   wall-clock time the outcome was observed
 * @param detail         free-form notes ("user said 'not now'", "tool=sendSms")
 */
data class Outcome(
    val type: OutcomeType,
    val actionClass: String?,
    val dedupeKey: String?,
    val situationType: SituationType? = null,
    val goalId: Long? = null,
    val planId: String? = null,
    val traceId: Long? = null,
    val occurredAtMs: Long,
    val detail: String? = null,
)
