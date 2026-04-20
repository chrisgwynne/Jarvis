package com.jarvis.assistant.core.decisions

import com.jarvis.assistant.core.situations.SituationType

/**
 * Decision — the unified vocabulary that both pipelines speak.
 *
 * The reactive pipeline (IntentClassifier → ConversationClassifier →
 * ToolUsePolicy → ToolRegistry → ToolDispatcher → LLM) and the proactive
 * pipeline (EventGenerator → EventScorer → DecisionEngine → dispatcher)
 * used to produce different shapes. [DecisionBrain] wraps both and returns
 * this one type so telemetry, logging and tests can reason about outcomes
 * uniformly.
 *
 * Neither pipeline is rewritten — [DecisionBrain] delegates to the existing
 * classifiers and engines and packages their outputs here.
 */
sealed class Decision {
    abstract val trace: DecisionTrace

    /** Plain conversational reply — no tool, no plan. */
    data class RespondChat(override val trace: DecisionTrace) : Decision()

    /** Run a single [toolName] via existing ToolDispatcher. */
    data class ExecuteTool(
        val toolName: String,
        override val trace: DecisionTrace
    ) : Decision()

    /** Propose/execute a multi-step plan via existing PlanRunner. */
    data class ExecutePlan(
        val stepCount: Int,
        override val trace: DecisionTrace
    ) : Decision()

    /** Ask the user one short question before acting. */
    data class AskClarification(
        val prompt: String,
        override val trace: DecisionTrace
    ) : Decision()

    /** Hold this suggestion for a later tick — the signal is live but the moment is wrong. */
    data class Defer(
        val reason: SuppressionReason,
        override val trace: DecisionTrace
    ) : Decision()

    /** Deliberately do nothing. Carries the [SuppressionReason] for telemetry. */
    data class Ignore(
        val reason: SuppressionReason,
        override val trace: DecisionTrace
    ) : Decision()
}

/**
 * Structured breadcrumb explaining how a Decision was reached.
 *
 * [source] distinguishes reactive vs proactive origins. [classifiers] lists
 * the named stages consulted (e.g. "IntentClassifier", "ConversationClassifier").
 * [notes] is a short free-text postscript — kept compact so the trace store
 * stays cheap to append per tick.
 */
data class DecisionTrace(
    val source: Source,
    val classifiers: List<String> = emptyList(),
    val suppressionReason: SuppressionReason? = null,
    val notes: String? = null,
    /** Top active situation at the moment of the decision, if any.
     *  Populated when the proactive tick ran the situation evaluator. */
    val situationType: SituationType? = null,
    /** Active goal the decision was advancing, if any. */
    val goalId: Long? = null,
    /** Plan id when the decision led to a multi-step execution. */
    val planId: String? = null,
    /** Outcome id if an outcome was recorded as part of this decision (rare —
     *  most outcomes arrive later when the verdict resolves). */
    val outcomeId: Long? = null,
) {
    enum class Source { REACTIVE, PROACTIVE }
}
