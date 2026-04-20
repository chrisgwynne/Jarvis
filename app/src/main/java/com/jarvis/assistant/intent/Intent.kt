package com.jarvis.assistant.intent

/**
 * Primary spoken-keyword intents recognised by [KeywordIntentRouter].
 *
 * Ordering does NOT imply priority — [IntentConflictResolver] owns ranking.
 * The router produces at most one primary intent per utterance.
 */
enum class PrimaryIntent {
    OBSERVE_SCREEN,
    ACT_ON_CONTEXT,
    STORE_CONTEXT,
    RECALL_RECENT_CONTEXT,
    DRAFT_REPLY,
    INTERRUPT_ASSISTANT,
    PAUSE_ASSISTANT,
    RESUME_ASSISTANT,
    CHANGE_RESPONSE_STYLE,
}

/**
 * Secondary tags attached to the primary intent.
 *
 *   STORE_RESULT     — e.g. "look at this AND remember it"
 *   REWRITE_MODE     — intrinsic to "say this better" (DRAFT_REPLY)
 *   STYLE_CONCISE    — "short answer"  (CHANGE_RESPONSE_STYLE carries this)
 *   STYLE_EXPANDED   — "explain properly"
 *   LABEL_PROVIDED   — "save this as {label}" — envelope.label is populated
 */
enum class IntentModifier {
    STORE_RESULT,
    REWRITE_MODE,
    STYLE_CONCISE,
    STYLE_EXPANDED,
    LABEL_PROVIDED,
}

enum class RiskLevel { LOW, MEDIUM, HIGH }
