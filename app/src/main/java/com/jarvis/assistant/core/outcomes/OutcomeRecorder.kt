package com.jarvis.assistant.core.outcomes

import com.jarvis.assistant.core.decisions.ActionLedger
import com.jarvis.assistant.core.situations.SituationType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * OutcomeRecorder — single write path for [Outcome]s.
 *
 * This is the closed-loop sink. Anywhere the runtime learns what happened
 * after a decision — tool returned, plan halted, user said "not now",
 * follow-up expired — the caller reports it here and the recorder fans it
 * out:
 *
 *   - persisted to [OutcomeDao] so learning survives process death,
 *   - reflected on [ActionLedger] so scoring / cooldown adapt immediately,
 *   - broadcast on [outcomes] so listeners (memory feedback, trace writer,
 *     tests) can react.
 *
 * The fan-out is intentionally one-way: consumers never write back through
 * the recorder. That keeps the loop acyclic and easy to reason about.
 */
class OutcomeRecorder(
    private val dao: OutcomeDao,
    private val ledger: ActionLedger,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val _outcomes = MutableSharedFlow<Outcome>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    /** Hot stream of outcomes as they are recorded. Memory and telemetry
     *  subscribe here. */
    val outcomes: SharedFlow<Outcome> = _outcomes.asSharedFlow()

    /** Record an outcome and fan it out to ledger + dao. */
    suspend fun record(outcome: Outcome): Long {
        reflectOnLedger(outcome)
        val id = dao.insert(outcome.toEntity())
        _outcomes.emit(outcome)
        return id
    }

    /**
     * Convenience for a proactive dispatch that has just happened and we
     * don't yet know the user's reaction. Records ACCEPTED / IGNORED when
     * the later verdict resolves via [resolveProactiveVerdict].
     */
    suspend fun recordProactiveDispatched(
        dedupeKey: String,
        actionClass: String?,
        situationType: SituationType? = null,
        traceId: Long? = null,
    ): Long = record(
        Outcome(
            type = OutcomeType.ACTED_ON, // dispatched, not yet verdictted; ledger-only
            actionClass = actionClass,
            dedupeKey = dedupeKey,
            situationType = situationType,
            traceId = traceId,
            occurredAtMs = nowMs(),
            detail = "dispatched",
        )
    )

    /** Called when a pending proactive verdict resolves — accepted or ignored. */
    suspend fun resolveProactiveVerdict(
        dedupeKey: String,
        actionClass: String?,
        accepted: Boolean,
        situationType: SituationType? = null,
    ): Long = record(
        Outcome(
            type = if (accepted) OutcomeType.ACCEPTED else OutcomeType.IGNORED,
            actionClass = actionClass,
            dedupeKey = dedupeKey,
            situationType = situationType,
            occurredAtMs = nowMs(),
            detail = if (accepted) "user engaged" else "no engagement",
        )
    )

    /** Called by [com.jarvis.assistant.runtime.plan.PlanRunner] when a plan
     *  completes end-to-end or halts. */
    suspend fun recordPlanOutcome(
        planId: String,
        goalId: Long?,
        completed: Boolean,
        detail: String? = null,
    ): Long = record(
        Outcome(
            type = if (completed) OutcomeType.PLAN_COMPLETED else OutcomeType.PLAN_HALTED,
            actionClass = null,
            dedupeKey = "plan:$planId",
            goalId = goalId,
            planId = planId,
            occurredAtMs = nowMs(),
            detail = detail,
        )
    )

    /** Called when the reactive path re-classifies a suggestion as wrong
     *  (undo issued, "no, the other one" within the confirmation window). */
    suspend fun recordUserCorrection(
        actionClass: String?,
        dedupeKey: String?,
        detail: String?,
        situationType: SituationType? = null,
    ): Long = record(
        Outcome(
            type = OutcomeType.USER_CORRECTED,
            actionClass = actionClass,
            dedupeKey = dedupeKey,
            situationType = situationType,
            occurredAtMs = nowMs(),
            detail = detail,
        )
    )

    /** Called when a gate / suppression reason kept the agent quiet. */
    suspend fun recordSuppressed(
        actionClass: String?,
        dedupeKey: String?,
        reason: String,
        traceId: Long? = null,
    ): Long = record(
        Outcome(
            type = OutcomeType.SUPPRESSED,
            actionClass = actionClass,
            dedupeKey = dedupeKey,
            traceId = traceId,
            occurredAtMs = nowMs(),
            detail = reason,
        )
    )

    /** Called when a tool-level action returned a failure. */
    suspend fun recordFailure(
        actionClass: String?,
        dedupeKey: String?,
        detail: String,
    ): Long = record(
        Outcome(
            type = OutcomeType.FAILED,
            actionClass = actionClass,
            dedupeKey = dedupeKey,
            occurredAtMs = nowMs(),
            detail = detail,
        )
    )

    /** Retrieve the recent window of outcomes. */
    suspend fun recent(windowMs: Long = DEFAULT_WINDOW_MS, limit: Int = 200): List<Outcome> =
        dao.recent(sinceMs = nowMs() - windowMs, limit = limit).map { it.toOutcome() }

    /** Count recent outcomes of a given type for a class; drives memory feedback. */
    suspend fun countRecent(actionClass: String, type: OutcomeType, windowMs: Long = DEFAULT_WINDOW_MS): Int =
        dao.countOfType(actionClass = actionClass, type = type.name, sinceMs = nowMs() - windowMs)

    // ── internal ────────────────────────────────────────────────────────────

    private fun reflectOnLedger(outcome: Outcome) {
        val cls = outcome.actionClass
        val key = outcome.dedupeKey
        when (outcome.type) {
            OutcomeType.ACCEPTED -> if (key != null) ledger.recordVerdict(key, accepted = true, actionClass = cls)
            OutcomeType.IGNORED -> if (key != null) ledger.recordVerdict(key, accepted = false, actionClass = cls)
            OutcomeType.USER_CORRECTED -> if (key != null) ledger.recordVerdict(key, accepted = false, actionClass = cls)
            else -> Unit // other types don't directly move cooldown; memory feedback handles learning
        }
    }

    companion object {
        private const val DEFAULT_WINDOW_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
    }
}

private fun Outcome.toEntity(): OutcomeEntity = OutcomeEntity(
    type = type.name,
    actionClass = actionClass,
    dedupeKey = dedupeKey,
    situationType = situationType?.name,
    goalId = goalId,
    planId = planId,
    traceId = traceId,
    occurredAtMs = occurredAtMs,
    detail = detail,
)

private fun OutcomeEntity.toOutcome(): Outcome = Outcome(
    type = runCatching { OutcomeType.valueOf(type) }.getOrDefault(OutcomeType.IGNORED),
    actionClass = actionClass,
    dedupeKey = dedupeKey,
    situationType = situationType?.let { runCatching { SituationType.valueOf(it) }.getOrNull() },
    goalId = goalId,
    planId = planId,
    traceId = traceId,
    occurredAtMs = occurredAtMs,
    detail = detail,
)
