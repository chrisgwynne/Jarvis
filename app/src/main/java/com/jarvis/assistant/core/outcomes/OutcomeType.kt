package com.jarvis.assistant.core.outcomes

/**
 * OutcomeType — how a decision the agent made eventually panned out.
 *
 * Emitted by [OutcomeRecorder] after the decision leaves the pipeline and
 * the user reacts (or fails to react). Fanned out to [ActionLedger] /
 * [CooldownStore] / [MemoryPolicy] so scoring can learn from lived
 * experience rather than scripted heuristics alone.
 *
 * Values are deliberately coarse. Finer-grained distinctions (e.g. "user
 * corrected via undo" vs "user corrected via follow-up edit") are stored
 * in the outcome's free-form `detail` field, not in the enum.
 */
enum class OutcomeType {
    /** User explicitly accepted: replied yes, confirmed, engaged. */
    ACCEPTED,
    /** User acted on the suggestion without confirming verbally (did the thing). */
    ACTED_ON,
    /** Surfaced but the user didn't engage; timed out or moved on. */
    IGNORED,
    /** User corrected the action (undo, "no not that one", "actually X instead"). */
    USER_CORRECTED,
    /** Action dispatched but the tool returned a failure. */
    FAILED,
    /** Suppressed before dispatch by a gate / memory dislike / presence. */
    SUPPRESSED,
    /** Plan or multi-step chain completed end to end. */
    PLAN_COMPLETED,
    /** Plan or multi-step chain halted and was rolled back. */
    PLAN_HALTED,
}
