package com.jarvis.assistant.conversation

/**
 * InterruptionClassifier — decides what to do with an utterance that arrived
 * while Jarvis was still speaking.
 *
 * ORDER OF CHECKS (first match wins):
 *   1. URGENT         — explicit stop / wait / hold-on / cancel phrases.
 *   2. CORRECTION     — "no", "actually", "I meant...", "wait, I..."
 *   3. CONTINUE       — "go on", "keep going", "carry on", "continue"
 *   4. CLARIFICATION  — question forms that share keywords with the response
 *                        OR short probing questions ("what do you mean?").
 *   5. REPLACEMENT    — first-person statement or command after a barge-in
 *                        that doesn't share topic with the response.
 *   6. UNRELATED      — anything else; treat as a fresh topic.
 *
 * DESIGN NOTE:
 *   Intentionally cheap and rule-based — the barge-in path is latency-sensitive.
 *   Wrong classifications are not catastrophic: they just mean the user may
 *   need to repeat themselves once.
 */
object InterruptionClassifier {

    // ── Explicit categories ───────────────────────────────────────────────────

    private val URGENT_PHRASES = listOf(
        "stop", "wait", "wait a second", "wait a moment", "hold on",
        "hold up", "pause", "quiet", "be quiet", "shut up", "enough",
        "cancel", "cancel that", "never mind", "forget it", "nevermind"
    )

    private val CONTINUE_PHRASES = listOf(
        "go on", "continue", "keep going", "carry on", "and then",
        "please continue", "yes continue", "go ahead"
    )

    private val CORRECTION_PHRASES = listOf(
        "no ", "nope ", "actually ", "i meant ", "i mean ", "wait i ",
        "sorry i ", "not that", "wrong", "that's wrong", "not what i ",
        "let me rephrase", "rephrase that"
    )

    private val CLARIFY_PHRASES = listOf(
        "what do you mean", "what does that mean", "can you explain",
        "how do you mean", "in what way", "what's that", "say that again",
        "repeat that", "sorry what", "come again"
    )

    /**
     * Classify [utterance] against the response that was being spoken.
     *
     * @param utterance    The new user input captured post-interrupt.
     * @param spokenSoFar  What Jarvis had said before being cut off (may be empty).
     */
    fun classify(utterance: String, spokenSoFar: String): InterruptionType {
        val lower = utterance.lowercase().trim()
        if (lower.isBlank()) return InterruptionType.CONTINUE

        // 1. URGENT — exact-match or startsWith on hard-stop phrases
        if (URGENT_PHRASES.any { phrase ->
                lower == phrase || lower.startsWith("$phrase ") ||
                lower.startsWith("$phrase,") || lower.startsWith("$phrase.")
            }) return InterruptionType.URGENT

        // 2. CORRECTION — leading corrective phrases
        if (CORRECTION_PHRASES.any { lower.startsWith(it) })
            return InterruptionType.CORRECTION

        // 3. CONTINUE — leading "go on" style phrases (must be short utterance)
        if (lower.split(" ").size <= 4 &&
            CONTINUE_PHRASES.any { lower == it || lower.startsWith(it) })
            return InterruptionType.CONTINUE

        // 4. CLARIFICATION — explicit "what do you mean" OR a short question
        //    that shares a topic word with the unfinished response.
        if (CLARIFY_PHRASES.any { lower.contains(it) }) return InterruptionType.CLARIFICATION
        if (isQuestion(lower) && sharesTopic(lower, spokenSoFar) &&
            lower.split(" ").size <= 10) return InterruptionType.CLARIFICATION

        // 5. REPLACEMENT — command or first-person statement, same conversation
        //    but doesn't share topic with response → user swapped the question.
        if ((isCommand(lower) || isFirstPerson(lower)) &&
            !sharesTopic(lower, spokenSoFar)) return InterruptionType.REPLACEMENT

        // 6. Default: unrelated new topic
        return InterruptionType.UNRELATED
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val QUESTION_STARTERS = setOf(
        "what", "who", "when", "where", "why", "how",
        "is", "are", "was", "were", "do", "does", "did",
        "can", "could", "will", "would", "should"
    )

    private fun isQuestion(lower: String): Boolean {
        if (lower.endsWith("?")) return true
        val first = lower.split(" ").firstOrNull() ?: return false
        return first in QUESTION_STARTERS
    }

    private val COMMAND_STARTERS = setOf(
        "call", "text", "open", "launch", "start", "stop", "set",
        "remind", "play", "pause", "send", "turn", "switch", "take"
    )

    private fun isCommand(lower: String): Boolean {
        val first = lower.split(" ").firstOrNull() ?: return false
        return first in COMMAND_STARTERS
    }

    private fun isFirstPerson(lower: String): Boolean =
        lower.startsWith("i ") || lower.startsWith("i'") ||
        lower.startsWith("my ") || lower.startsWith("we ") ||
        lower.startsWith("we'")

    /**
     * Cheap topic-overlap check: share at least one content word (>3 chars,
     * not a stop-word) between the utterance and what Jarvis was saying.
     */
    private fun sharesTopic(utterance: String, spoken: String): Boolean {
        if (spoken.isBlank()) return false
        val spokenWords = spoken.lowercase()
            .split(Regex("""\W+"""))
            .filter { it.length > 3 && it !in STOP_WORDS }
            .toSet()
        if (spokenWords.isEmpty()) return false
        val utteranceWords = utterance.split(Regex("""\W+"""))
            .filter { it.length > 3 && it !in STOP_WORDS }
        return utteranceWords.any { it in spokenWords }
    }

    private val STOP_WORDS = setOf(
        "this", "that", "with", "from", "your", "have", "been", "they",
        "them", "their", "would", "could", "should", "there", "where",
        "which", "what", "when", "than", "then", "also", "about", "into",
        "some", "just", "like", "well", "yeah", "okay", "sure", "right"
    )
}
