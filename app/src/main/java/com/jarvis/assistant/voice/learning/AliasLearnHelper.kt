package com.jarvis.assistant.voice.learning

import android.util.Log

/**
 * AliasLearnHelper — pure parsing logic that converts a "no, I meant X"
 * style correction into a (heard, intended, context) triple and records
 * it in [AliasLearningStore].
 *
 * Kept pure (no Android context dependencies) so unit tests can exercise
 * every branch deterministically.
 *
 * Expected correction phrases:
 *   - "no, I meant <X>"
 *   - "I said <X>"
 *   - "not <X>, <Y>"
 *   - "I meant <X> not <Y>"
 *
 * The `heard` token is found by scanning [previousTranscript] for the
 * token with the smallest Levenshtein distance to `intended` — that's
 * the word the recogniser got wrong.
 */
object AliasLearnHelper {

    private const val TAG = "AliasLearnHelper"

    /** Public for unit tests. */
    data class Parsed(val heard: String, val intended: String, val ctx: AliasLearningStore.Context_)

    /**
     * Try to parse [correctionUtter] in the context of
     * [previousTranscript] and record the alias.  Returns the parsed
     * triple if a record was made, or null if the correction couldn't be
     * resolved.
     */
    fun tryRecord(
        previousTranscript: String,
        correctionUtter:    String,
        store:              AliasLearningStore
    ): Parsed? {
        if (previousTranscript.isBlank()) {
            Log.d(TAG, "[ALIAS_LEARN_SKIPPED_NO_RECENT_TRANSCRIPT] " +
                "correction=\"$correctionUtter\"")
            return null
        }
        val parsed = parse(previousTranscript, correctionUtter)
        if (parsed == null) {
            Log.d(TAG, "[ALIAS_LEARN_SKIPPED_UNCLEAR_INTENT] " +
                "previous=\"$previousTranscript\" correction=\"$correctionUtter\"")
            return null
        }
        store.record(parsed.heard, parsed.intended, parsed.ctx)
        Log.d(TAG, "[ALIAS_LEARN_RETRY_LAST_COMMAND] " +
            "heard=\"${parsed.heard}\" intended=\"${parsed.intended}\" ctx=${parsed.ctx.tag}")
        return parsed
    }

    /**
     * Pure parser, exposed for unit tests.  Returns the (heard, intended,
     * ctx) triple or null when the correction shape doesn't match any
     * known phrasing.
     */
    fun parse(previousTranscript: String, correctionUtter: String): Parsed? {
        val intended = extractIntended(correctionUtter) ?: return null
        if (intended.isBlank()) return null

        val heard = closestTokenIn(previousTranscript, intended) ?: return null

        val ctx = inferContext(previousTranscript) ?: AliasLearningStore.Context_.GENERIC
        return Parsed(heard, intended, ctx)
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /** Captures the intended word/phrase from a correction utterance. */
    private val PATTERNS: List<Regex> = listOf(
        // "no, I meant WhatsApp"
        Regex("""\b(?:no,?\s+)?i\s+(?:meant|mean)\s+([\w'\- ]+?)(?:\s+not\b|[.!?]|$)""", RegexOption.IGNORE_CASE),
        // "I said Cath"
        Regex("""\bi\s+said\s+([\w'\- ]+?)(?:[.!?]|$)""",                                 RegexOption.IGNORE_CASE),
        // "not cat, Cath"
        Regex("""\bnot\s+[\w'\- ]+?,\s+([\w'\- ]+?)(?:[.!?]|$)""",                        RegexOption.IGNORE_CASE),
        // bare "WhatsApp" right after "no" (last-ditch)
        Regex("""^no[,]?\s+([\w'\- ]+?)$""",                                              RegexOption.IGNORE_CASE),
    )

    private fun extractIntended(text: String): String? {
        val t = text.trim()
        for (p in PATTERNS) {
            val m = p.find(t) ?: continue
            val intended = m.groupValues[1].trim()
            if (intended.length in 2..40) return intended
        }
        return null
    }

    /**
     * Among the tokens in [haystack], return the one with the smallest
     * Levenshtein distance to [needle].  Limits to distance ≤ 3 (or 1 + needle.length/3)
     * so unrelated tokens don't get matched.
     */
    private fun closestTokenIn(haystack: String, needle: String): String? {
        val tokens = Regex("""[\w']+""").findAll(haystack)
            .map { it.value }
            .filter { it.length >= 2 }
            .toList()
        // Homophone mishears can have surprisingly high edit distance —
        // "what's" → "WhatsApp" is 4, "spot" → "Spotify" is 3.  Use half the
        // needle length with a floor of 3 so short names still match strictly
        // but longer brand names tolerate the typical recognition slip.
        val maxDist = maxOf(3, needle.length / 2)
        var best: Pair<String, Int>? = null
        for (tok in tokens) {
            // Skip exact substring matches — those are not the mishear.
            if (tok.equals(needle, ignoreCase = true)) continue
            val d = levenshtein(tok.lowercase(), needle.lowercase())
            if (d > maxDist) continue
            if (best == null || d < best.second) best = tok to d
        }
        return best?.first
    }

    private fun inferContext(previous: String): AliasLearningStore.Context_? {
        val lower = previous.lowercase()
        return when {
            Regex("""\b(send|text|message|whatsapp|wa|email)\b""").containsMatchIn(lower)  ->
                AliasLearningStore.Context_.MESSAGING
            Regex("""\b(call|ring|phone|to)\s+[a-z]""").containsMatchIn(lower)             ->
                AliasLearningStore.Context_.CONTACT
            Regex("""\b(open|launch|start)\b""").containsMatchIn(lower)                    ->
                AliasLearningStore.Context_.APP
            Regex("""\b(turn|switch|set|dim|brighten|lock|unlock)\b""").containsMatchIn(lower) ->
                AliasLearningStore.Context_.DEVICE
            else -> null
        }
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }
}
