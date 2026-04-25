package com.jarvis.assistant.intent

/**
 * KeywordDictionary — the exact-match phrase book.
 *
 * STAGE 1 of [KeywordIntentRouter.route] scans every [Entry] against the
 * normalised transcript.  Each hit contributes a candidate intent + any
 * intrinsic modifiers (e.g. "say this better" always carries REWRITE_MODE).
 *
 * DESIGN NOTES:
 *   * We anchor patterns loosely (word boundaries only) so they match when
 *     embedded in a longer utterance ("stop and look at this").  The
 *     conflict resolver then picks the highest-priority hit.
 *   * Intrinsic modifiers are declared with the entry so the router doesn't
 *     need a separate modifier table for primaries that always carry them.
 *   * baseConfidence is the envelope's confidence when an entry fires.  We
 *     use 0.95 everywhere so exact matches are always strong; fuzzy falls
 *     back to lower numbers via [FuzzyIntentMatcher].
 */
object KeywordDictionary {

    data class Entry(
        val pattern:         Regex,
        val intent:          PrimaryIntent,
        val intrinsic:       List<IntentModifier> = emptyList(),
        /** Group name in [pattern] to capture as the envelope.label, or null. */
        val labelGroup:      String? = null,
        val baseConfidence:  Double = 0.95,
    )

    /**
     * Match outcome for a single entry.
     */
    data class Match(
        val intent:     PrimaryIntent,
        val modifiers:  List<IntentModifier>,
        val label:      String?,
        val confidence: Double,
        val matchedText: String,
    )

    // ── Entries ──────────────────────────────────────────────────────────────
    // Ordered by intent for readability; priority resolution is separate.

    val entries: List<Entry> = listOf(
        // OBSERVE_SCREEN
        Entry(Regex("""\blook\s+at\s+this\b""", RegexOption.IGNORE_CASE), PrimaryIntent.OBSERVE_SCREEN),
        Entry(Regex("""\bcheck\s+this\b""",     RegexOption.IGNORE_CASE), PrimaryIntent.OBSERVE_SCREEN),

        // ACT_ON_CONTEXT
        Entry(Regex("""\bdo\s+this\b""",        RegexOption.IGNORE_CASE), PrimaryIntent.ACT_ON_CONTEXT),
        Entry(Regex("""\bhandle\s+this\b""",    RegexOption.IGNORE_CASE), PrimaryIntent.ACT_ON_CONTEXT),

        // STORE_CONTEXT — label-carrying form first so it wins over the plain "save this"
        Entry(
            pattern    = Regex("""\bsave\s+this\s+as\s+(?<label>[\p{L}\p{N}\s'"-]{1,60})\b""", RegexOption.IGNORE_CASE),
            intent     = PrimaryIntent.STORE_CONTEXT,
            intrinsic  = listOf(IntentModifier.LABEL_PROVIDED),
            labelGroup = "label",
        ),
        Entry(Regex("""\bremember\s+this\b""",  RegexOption.IGNORE_CASE), PrimaryIntent.STORE_CONTEXT),
        Entry(Regex("""\bsave\s+this\b""",      RegexOption.IGNORE_CASE), PrimaryIntent.STORE_CONTEXT),

        // RECALL_RECENT_CONTEXT
        Entry(Regex("""\bwhat\s+was\s+i\s+doing\b""",   RegexOption.IGNORE_CASE), PrimaryIntent.RECALL_RECENT_CONTEXT),
        Entry(Regex("""\bwhat\s+did\s+that\s+say\b""",  RegexOption.IGNORE_CASE), PrimaryIntent.RECALL_RECENT_CONTEXT),

        // DRAFT_REPLY
        Entry(
            pattern   = Regex("""\bsay\s+this\s+better\b""", RegexOption.IGNORE_CASE),
            intent    = PrimaryIntent.DRAFT_REPLY,
            intrinsic = listOf(IntentModifier.REWRITE_MODE),
        ),
        Entry(Regex("""\breply\s+to\s+this\b""", RegexOption.IGNORE_CASE), PrimaryIntent.DRAFT_REPLY),

        // INTERRUPT_ASSISTANT / PAUSE / RESUME — anchored to avoid matching
        // "stop it" only when the user means control, not "stop the music"
        // (MediaControlTool handles that via its own triggers).  We keep the
        // pattern loose here and rely on [IntentConflictResolver] to honour
        // control signals over everything else.
        Entry(Regex("""\bstop\b""",         RegexOption.IGNORE_CASE), PrimaryIntent.INTERRUPT_ASSISTANT),
        Entry(Regex("""\bwait\b""",         RegexOption.IGNORE_CASE), PrimaryIntent.PAUSE_ASSISTANT),
        Entry(Regex("""\bcarry\s+on\b""",   RegexOption.IGNORE_CASE), PrimaryIntent.RESUME_ASSISTANT),

        // CHANGE_RESPONSE_STYLE
        Entry(
            pattern   = Regex("""\bshort\s+answer\b""", RegexOption.IGNORE_CASE),
            intent    = PrimaryIntent.CHANGE_RESPONSE_STYLE,
            intrinsic = listOf(IntentModifier.STYLE_CONCISE),
        ),
        Entry(
            pattern   = Regex("""\bexplain\s+properly\b""", RegexOption.IGNORE_CASE),
            intent    = PrimaryIntent.CHANGE_RESPONSE_STYLE,
            intrinsic = listOf(IntentModifier.STYLE_EXPANDED),
        ),
    )

    /**
     * Standalone modifier phrases that attach to whatever primary is picked.
     * e.g. "look at this and remember it" → OBSERVE_SCREEN + STORE_RESULT.
     */
    val modifierPhrases: List<Pair<Regex, IntentModifier>> = listOf(
        Regex("""\band\s+(?:remember|save)\s+(?:it|this|that)\b""", RegexOption.IGNORE_CASE)
            to IntentModifier.STORE_RESULT,
    )

    /** Scan [text] and return every exact-match hit (may be empty). */
    fun matchAll(text: String): List<Match> {
        val hits = mutableListOf<Match>()
        for (entry in entries) {
            val m = entry.pattern.find(text) ?: continue
            val label = entry.labelGroup?.let { m.groups[it]?.value?.trim()?.trim('"', '\'') }
            hits += Match(
                intent      = entry.intent,
                modifiers   = entry.intrinsic.toList(),
                label       = label?.ifBlank { null },
                confidence  = entry.baseConfidence,
                matchedText = m.value,
            )
        }
        return hits
    }

    /** Apply [modifierPhrases] to [text] and return the modifiers triggered. */
    fun standaloneModifiers(text: String): List<IntentModifier> {
        val out = mutableListOf<IntentModifier>()
        for ((pattern, mod) in modifierPhrases) {
            if (pattern.containsMatchIn(text)) out += mod
        }
        return out
    }
}
