package com.jarvis.assistant.core.proactive

/**
 * Catalog of user-facing proactive strings.
 *
 * Previously these lived inline inside [com.jarvis.assistant.proactive
 * .followup.ConversationalProactiveEngine] and in each trigger class under
 * [com.jarvis.assistant.core.decisions.triggers]. Consolidating them here
 * makes tone review possible in one place and keeps the voice consistent
 * across surfaces (per CLAUDE.md: calm, observant, direct — no alert-speak).
 *
 * Migration is incremental: callers can continue to inline their own strings
 * until moved. New proactive strings SHOULD land here first.
 */
object ProactiveStrings {

    /** Gap check-ins fired after prolonged user silence. */
    val gapCheckIns: List<String> = listOf(
        "You good?",
        "How's things?",
        "Been quiet — everything alright?",
        "Still around?",
        "How's it going?",
    )

    /**
     * Gap check-in variants for repeated ignore escalation. When the user has
     * ignored several check-ins in a row the engine should back off rather
     * than keep retrying verbatim. Returning an empty list here means "skip
     * the check-in entirely on this tick" — the caller must honour that.
     */
    val gapCheckInsQuiet: List<String> = listOf(
        // Intentionally empty: after N ignores the right answer is silence.
    )
}
