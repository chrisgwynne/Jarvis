package com.jarvis.assistant.intent

/**
 * IntentConflictResolver — picks one primary intent when multiple candidates
 * match the same utterance.
 *
 * PRIORITY LADDER (lowest rank number = highest priority):
 *   0  emergency interruption  — INTERRUPT / PAUSE / RESUME
 *   1  explicit control        — CHANGE_RESPONSE_STYLE
 *   2  explicit action         — ACT_ON_CONTEXT, DRAFT_REPLY
 *   3  explicit memory         — STORE_CONTEXT, RECALL_RECENT_CONTEXT
 *   4  explicit observe        — OBSERVE_SCREEN
 *   5  general chat            — unmatched (router returns null)
 *
 * TIE-BREAKING:
 *   Same priority → higher [confidence] wins.
 *   Same confidence → first candidate in the provided list wins (stable).
 */
class IntentConflictResolver {

    companion object {
        private val RANK: Map<PrimaryIntent, Int> = mapOf(
            PrimaryIntent.INTERRUPT_ASSISTANT   to 0,
            PrimaryIntent.PAUSE_ASSISTANT       to 0,
            PrimaryIntent.RESUME_ASSISTANT      to 0,
            PrimaryIntent.CHANGE_RESPONSE_STYLE to 1,
            PrimaryIntent.ACT_ON_CONTEXT        to 2,
            PrimaryIntent.DRAFT_REPLY           to 2,
            PrimaryIntent.STORE_CONTEXT         to 3,
            PrimaryIntent.RECALL_RECENT_CONTEXT to 3,
            PrimaryIntent.OBSERVE_SCREEN        to 4,
        )
    }

    /** Rank for [intent]. Lower = more urgent. */
    fun rank(intent: PrimaryIntent): Int = RANK[intent] ?: Int.MAX_VALUE

    /**
     * Pick the winning candidate from [matches].  Returns null when the list
     * is empty.  Stable: if two matches tie on both rank and confidence,
     * the one that appears first in [matches] wins.
     */
    fun pick(matches: List<KeywordDictionary.Match>): KeywordDictionary.Match? {
        if (matches.isEmpty()) return null
        // sortedWith is stable in Kotlin/JVM, so original order is preserved on ties.
        return matches.sortedWith(
            compareBy<KeywordDictionary.Match> { rank(it.intent) }
                .thenByDescending { it.confidence }
        ).first()
    }
}
