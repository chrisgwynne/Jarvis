package com.jarvis.assistant.core.decisions

import com.jarvis.assistant.proactive.ProactiveAction

/**
 * DecisionBrain — thin facade over the existing reactive and proactive
 * pipelines.
 *
 * It does **not** replace either chain. The reactive route still runs
 * IntentClassifier → ConversationClassifier → ToolUsePolicy →
 * ToolRegistry → ToolDispatcher → LLM inside [com.jarvis.assistant.runtime.JarvisRuntime];
 * the proactive route still runs EventGenerator → EventScorer →
 * DecisionEngine → TtsProactiveDispatcher inside
 * [com.jarvis.assistant.proactive.ProactiveEngine].
 *
 * What this facade gives us is a single sealed [Decision] type both
 * pipelines can be *wrapped in* so telemetry, logging, and future tests
 * speak the same vocabulary. The intent is incremental adoption — new
 * call sites create a [DecisionBrain] to translate a concrete
 * [ReactiveOutcome] or [ProactiveAction] into a [Decision] and forward it
 * to the legacy dispatch path. Nothing in the existing pipelines is
 * routed through here yet; this is infrastructure.
 */
class DecisionBrain {

    /**
     * Package a finished reactive outcome as a [Decision]. The runtime
     * computes the outcome with its existing classifiers; this merely
     * labels it.
     */
    fun labelReactive(outcome: ReactiveOutcome, classifiers: List<String> = emptyList()): Decision {
        val trace = DecisionTrace(
            source      = DecisionTrace.Source.REACTIVE,
            classifiers = classifiers,
            notes       = outcome.notes,
        )
        return when (outcome) {
            is ReactiveOutcome.Chat       -> Decision.RespondChat(trace)
            is ReactiveOutcome.Tool       -> Decision.ExecuteTool(outcome.toolName, trace)
            is ReactiveOutcome.Plan       -> Decision.ExecutePlan(outcome.stepCount, trace)
            is ReactiveOutcome.Clarify    -> Decision.AskClarification(outcome.question, trace)
            is ReactiveOutcome.Suppressed -> Decision.Ignore(outcome.reason, trace.copy(suppressionReason = outcome.reason))
        }
    }

    /**
     * Wrap a concrete [ProactiveAction] (the output of the existing
     * [com.jarvis.assistant.proactive.DecisionEngine]) as a [Decision].
     */
    fun labelProactive(action: ProactiveAction, classifiers: List<String> = emptyList()): Decision {
        val trace = DecisionTrace(
            source      = DecisionTrace.Source.PROACTIVE,
            classifiers = classifiers,
        )
        return when (action) {
            is ProactiveAction.SpeakAction   -> Decision.ExecuteTool(
                toolName = "tts:${action.sourceType.name.lowercase()}",
                trace    = trace.copy(notes = action.dedupeKey),
            )
            is ProactiveAction.PassiveAction -> Decision.ExecuteTool(
                toolName = "notify:${action.sourceType.name.lowercase()}",
                trace    = trace.copy(notes = action.dedupeKey),
            )
            is ProactiveAction.NoAction      -> Decision.Ignore(
                reason = action.reason,
                trace  = trace.copy(suppressionReason = action.reason),
            )
        }
    }
}

/**
 * Reactive-side verdict — what JarvisRuntime's classifier chain
 * actually decided. Kept minimal so the runtime isn't restructured to
 * produce it; we pass in whichever variant matches after the existing
 * flow finishes.
 */
sealed class ReactiveOutcome {
    open val notes: String? = null

    data class Chat(override val notes: String? = null) : ReactiveOutcome()
    data class Tool(val toolName: String, override val notes: String? = null) : ReactiveOutcome()
    data class Plan(val stepCount: Int, override val notes: String? = null) : ReactiveOutcome()
    data class Clarify(val question: String, override val notes: String? = null) : ReactiveOutcome()
    data class Suppressed(val reason: SuppressionReason, override val notes: String? = null) : ReactiveOutcome()
}
