package com.jarvis.assistant.orchestration

/**
 * ConversationAction — typed intent extracted from user utterances by
 * [IntentClassifier] before the request reaches ToolRegistry or the LLM.
 *
 * The classifier runs first: if it returns anything other than [PassThrough],
 * JarvisRuntime handles it directly and skips ToolRegistry + LLM for that turn.
 */
sealed class ConversationAction {

    // ── Memory actions ────────────────────────────────────────────────────────

    /** Store a structured fact about the user. */
    data class RememberFact(
        val key: String,
        val value: String,
        val rawInput: String
    ) : ConversationAction()

    /** User wants to know what Jarvis remembers about them. */
    data class RecallFact(val query: String) : ConversationAction()

    // ── Reminder / Timer actions ──────────────────────────────────────────────

    /** "Remind me to X at Y" / "Remind me in Z minutes" */
    data class CreateReminder(val rawInput: String) : ConversationAction()

    /** "Set a timer for X minutes" */
    data class CreateTimer(val rawInput: String) : ConversationAction()

    /** "What reminders do I have?" / "Show my timers" */
    object ListReminders : ConversationAction()

    /** "Cancel my reminder" / "Cancel the timer" */
    data class CancelReminder(val rawInput: String) : ConversationAction()

    // ── Suppression / preference actions ──────────────────────────────────────

    /**
     * "Don't tell me about X" / "stop suggesting X" / "never mention X again".
     *
     * Recorded in memory as a PREFERENCE fact and reflected in the
     * [com.jarvis.assistant.core.decisions.ActionLedger] so the proactive
     * scorer hard-suppresses the matching class on subsequent ticks.
     */
    data class MuteSuggestion(val topic: String) : ConversationAction()

    /** "Tell me about X again" / "you can mention X again" — inverse of [MuteSuggestion]. */
    data class UnmuteSuggestion(val topic: String) : ConversationAction()

    // ── Fall-through ──────────────────────────────────────────────────────────

    /** Not handled here — pass to ToolRegistry then the LLM. */
    object PassThrough : ConversationAction()
}
