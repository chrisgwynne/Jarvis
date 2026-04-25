package com.jarvis.assistant.intent

/**
 * FuzzyIntentMatcher — token-overlap fallback when [KeywordDictionary] has
 * no exact hit.
 *
 * APPROACH:
 *   * Each intent has a set of reference phrases (paraphrases of the
 *     canonical keyword).
 *   * Score = Jaccard similarity between the token sets of transcript and
 *     reference phrase, lightly boosted when a rare word (e.g. "carry")
 *     matches.
 *   * Best score across all references per intent wins; intent with the
 *     highest best-score wins overall, if it clears [MIN_SIMILARITY].
 *
 * WHY NOT EDIT DISTANCE:
 *   Short commands ("stop", "wait") collide under Levenshtein — "stop" and
 *   "top" are distance 1. Token-set Jaccard is more predictable for spoken
 *   commands that arrive as whole-word tokens from ASR.
 */
class FuzzyIntentMatcher {

    data class FuzzyMatch(val intent: PrimaryIntent, val confidence: Double)

    companion object {
        /** Minimum Jaccard score; below this, fuzzy returns null. */
        private const val MIN_SIMILARITY = 0.55

        /** Multiplier applied when a rare-word anchor overlaps. */
        private const val ANCHOR_BOOST = 1.15

        /**
         * Rare anchor words per intent — their presence gives a confidence
         * nudge so "can you carry on" still hits RESUME_ASSISTANT even when
         * padded with filler words.
         */
        private val ANCHORS: Map<PrimaryIntent, Set<String>> = mapOf(
            PrimaryIntent.OBSERVE_SCREEN        to setOf("look", "check", "screen"),
            PrimaryIntent.ACT_ON_CONTEXT        to setOf("do", "handle"),
            PrimaryIntent.STORE_CONTEXT         to setOf("remember", "save"),
            PrimaryIntent.RECALL_RECENT_CONTEXT to setOf("doing", "say", "said"),
            PrimaryIntent.DRAFT_REPLY           to setOf("reply", "better"),
            PrimaryIntent.INTERRUPT_ASSISTANT   to setOf("stop", "quiet", "cancel"),
            PrimaryIntent.PAUSE_ASSISTANT       to setOf("wait", "hold", "pause"),
            PrimaryIntent.RESUME_ASSISTANT      to setOf("carry", "continue", "resume"),
            PrimaryIntent.CHANGE_RESPONSE_STYLE to setOf("short", "explain", "properly", "verbose"),
        )

        /** Reference phrases per intent. */
        private val PHRASES: Map<PrimaryIntent, List<String>> = mapOf(
            PrimaryIntent.OBSERVE_SCREEN        to listOf("look at this", "check this", "look at my screen", "see this"),
            PrimaryIntent.ACT_ON_CONTEXT        to listOf("do this", "handle this", "take care of this"),
            PrimaryIntent.STORE_CONTEXT         to listOf("remember this", "save this", "store this"),
            PrimaryIntent.RECALL_RECENT_CONTEXT to listOf("what was i doing", "what did that say", "what was that"),
            PrimaryIntent.DRAFT_REPLY           to listOf("reply to this", "say this better", "draft a reply", "rewrite this"),
            PrimaryIntent.INTERRUPT_ASSISTANT   to listOf("stop", "be quiet", "cancel", "shut up"),
            PrimaryIntent.PAUSE_ASSISTANT       to listOf("wait", "hold on", "pause for a sec"),
            PrimaryIntent.RESUME_ASSISTANT     to listOf("carry on", "continue", "go on", "resume"),
            PrimaryIntent.CHANGE_RESPONSE_STYLE to listOf("short answer", "explain properly", "be concise", "be more verbose"),
        )
    }

    /** Return the best fuzzy match above [MIN_SIMILARITY], or null. */
    fun match(text: String): FuzzyMatch? {
        val queryTokens = tokenize(text)
        if (queryTokens.isEmpty()) return null

        var best: FuzzyMatch? = null
        for ((intent, phrases) in PHRASES) {
            val anchors = ANCHORS[intent].orEmpty()
            var intentBest = 0.0
            for (phrase in phrases) {
                val phraseTokens = tokenize(phrase)
                val score = jaccard(queryTokens, phraseTokens)
                val boosted = if (queryTokens.any { it in anchors }) score * ANCHOR_BOOST else score
                if (boosted > intentBest) intentBest = boosted
            }
            if (intentBest >= MIN_SIMILARITY && (best == null || intentBest > best.confidence)) {
                best = FuzzyMatch(intent = intent, confidence = intentBest.coerceAtMost(0.9))
            }
        }
        return best
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9']+"))
            .filter { it.isNotBlank() }
            .toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble()
        return inter / union
    }
}
