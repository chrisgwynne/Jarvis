package com.jarvis.assistant.voice.routing

/**
 * TranscriptNormalizer — strips STT punctuation noise AND canonicalises
 * common phrasings so local intent matchers can fire on clean text.
 *
 * Background.  Android's STT occasionally produces trailing punctuation or
 * stray symbols ("What time is it/", "open spotify."), or a leading
 * comma/dash artifact from a hot-mic restart.  Most tool regexes use `\s*$`
 * end-anchors and are case-insensitive, but a literal trailing slash or
 * period breaks the anchor and the tool is skipped — pushing what should
 * be a 200 ms local answer through the cloud LLM.
 *
 * Two passes, both pure / no Android dependency / unit-testable:
 *
 * 1.  [normalize] (the default): trim whitespace, collapse runs of
 *     whitespace, strip leading + trailing punctuation/symbols.  Preserves
 *     case AND internal punctuation that may be meaningful (apostrophes,
 *     hyphens, mid-word symbols).
 *
 * 2.  [normalizeForMatching] / `normalize(..., lowercase = true)`: pass 1
 *     plus lowercase plus a list of [PHRASE_REWRITES] that fold
 *     ("whats" → "what is", "switch on" → "turn on", "send a whatsapp" →
 *     "send whatsapp", …) into a single canonical form.  Use this when
 *     feeding a regex/entity parser; use [normalize] when the consumer is
 *     display / logging.
 */
object TranscriptNormalizer {

    /** Characters we consider punctuation noise at the edges of an utterance. */
    private const val EDGE_PUNCT = ".,!?/\\:;`\"'-_*#~|<>()[]{}"

    /** Whitespace runs (incl. tabs / newlines / non-breaking spaces). */
    private val WHITESPACE_RUN = Regex("""[\s ]+""")

    /**
     * Word-level rewrites applied AFTER punctuation/whitespace cleanup but
     * BEFORE intent matching.  Each pattern is anchored on word boundaries
     * so we never rewrite inside a proper noun.  Patterns assume the input
     * is already lowercased (so the rules only fire on the matching path).
     */
    private val PHRASE_REWRITES: List<Pair<Regex, String>> = listOf(
        // Contractions → canonical form so parsers don't carry both variants.
        Regex("""\bwhat'?s\b""")        to "what is",
        Regex("""\bthat'?s\b""")        to "that is",
        Regex("""\bit'?s\b""")          to "it is",
        Regex("""\bwhere'?s\b""")       to "where is",
        Regex("""\bwho'?s\b""")         to "who is",
        // Smart-home phrasings: "switch on/off" → "turn on/off".
        Regex("""\bswitch\s+on\b""")    to "turn on",
        Regex("""\bswitch\s+off\b""")   to "turn off",
        // Article strip after "turn" — "turn the kitchen lights" → "turn kitchen lights".
        Regex("""\bturn\s+the\b""")     to "turn",
        // Messaging "send a/an X" → "send X" so the channel token stays
        // adjacent to the verb for parser uniformity.
        Regex("""\bsend\s+an?\s+whatsapp\b""") to "send whatsapp",
        Regex("""\bsend\s+an?\s+text\b""")     to "send text",
        Regex("""\bsend\s+an?\s+sms\b""")      to "send sms",
        Regex("""\bsend\s+an?\s+message\b""")  to "send message",
        // Call phrasings.
        Regex("""\b(?:give\s+me\s+a\s+call\s+to|make\s+a\s+call\s+to|place\s+a\s+call\s+to)\b""")
            to "call",
    )

    /**
     * Return [raw] with edge punctuation stripped and whitespace collapsed.
     * Internal punctuation (apostrophes, hyphens) is preserved.
     *
     * When [lowercase] is true the result is also lowercased AND run
     * through [PHRASE_REWRITES] so downstream parsers see a canonical form.
     */
    fun normalize(raw: String, lowercase: Boolean = false): String {
        if (raw.isEmpty()) return raw
        var out = raw.replace(WHITESPACE_RUN, " ").trim()
        if (out.isEmpty()) return out

        // Trim edge punctuation greedily — walk from both ends so a
        // sequence like "?!/" at the tail is removed in one pass.
        var start = 0
        var end   = out.length
        while (start < end && out[start] in EDGE_PUNCT) start++
        while (end > start && out[end - 1] in EDGE_PUNCT) end--
        out = out.substring(start, end).trim()

        if (!lowercase) return out

        var lowered = out.lowercase()
        for ((pattern, replacement) in PHRASE_REWRITES) {
            lowered = pattern.replace(lowered, replacement)
        }
        // Replacements can introduce double spaces; collapse them.
        return lowered.replace(WHITESPACE_RUN, " ").trim()
    }

    /**
     * Aggressive normalisation: edge-punct strip + lowercase + phrase
     * rewrites.  Use this when the consumer is a regex / entity parser
     * that doesn't need the original casing.
     */
    fun normalizeForMatching(raw: String): String = normalize(raw, lowercase = true)

    /**
     * True when [a] and [b] differ only in edge punctuation / whitespace /
     * case.  Used by TranscriptCorrector to decline an N-best swap whose
     * "alternative" is the same utterance the recogniser already picked,
     * just with extra punctuation noise.  Case-only differences and edge
     * punctuation differences both count as "no real change" — phrase
     * rewrites are NOT applied because the comparison must hold without
     * the matching-side canonicalisation.
     */
    fun differsOnlyByPunctuation(a: String, b: String): Boolean =
        normalize(a, lowercase = false).lowercase() ==
            normalize(b, lowercase = false).lowercase()
}
