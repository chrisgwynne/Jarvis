package com.jarvis.assistant.voice.attention

/**
 * HumanConversationPatterns — phrases that, outside an active assistant
 * conversation window, are overwhelmingly human-to-human rather than
 * directed at Jarvis.
 *
 * Match rules:
 *  - All patterns are case-insensitive.
 *  - A match alone is enough to flag the transcript as HUMAN; the gate
 *    will still consider strong overrides (explicit "Jarvis", clear local
 *    command, active window).
 *  - Patterns must be specific.  Generic "what's…" / "can you…" without
 *    an action target are deliberately not included — those can be real
 *    questions for Jarvis.
 *
 * Add new patterns only with a real-world example in the comment.
 */
object HumanConversationPatterns {

    /** Strong negative — almost certainly human-directed. */
    val STRONG = listOf(
        // Mealtime / household
        Regex("""\bwhat\s+(?:do\s+you|d['’]?you)\s+want\s+for\s+(?:tea|dinner|lunch|breakfast|food)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bwhat['']?s\s+for\s+(?:tea|dinner|lunch|breakfast)\b""", RegexOption.IGNORE_CASE),

        // Coordination with humans in the room
        Regex("""\bare\s+you\s+(?:coming|going|ready|done|finished)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bcan\s+you\s+pass\s+me\b""", RegexOption.IGNORE_CASE),
        Regex("""\bcome\s+(?:here|upstairs|downstairs|over\s+here)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdid\s+you\s+(?:see|hear|do)\s+(?:that|this)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bI['']?ll\s+be\s+(?:there|down|up)\s+in\s+a\s+(?:minute|moment|sec)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bin\s+a\s+(?:minute|moment|sec)\b""", RegexOption.IGNORE_CASE),

        // Filler / acknowledgements that are never commands
        Regex("""^(?:yeah|yep|nope|nah|no\s+worries|okay\s+then|alright\s+then|sure\s+thing|fair\s+enough|cool|sounds\s+good)\b[.!?]?\s*$""", RegexOption.IGNORE_CASE),

        // "What are you doing" — almost always to a human.  If the user
        // means Jarvis they typically prefix "hey Jarvis" or ask
        // "what are you doing for me" / similar.
        Regex("""^what\s+are\s+you\s+doing\??$""", RegexOption.IGNORE_CASE),
    )

    /** Soft negative — likely human, but not certain.  Pushes the score down. */
    val SOFT = listOf(
        Regex("""\bthank\s+you\b""", RegexOption.IGNORE_CASE),    // "thank you" alone usually to a human
        Regex("""\bsorry\b""",       RegexOption.IGNORE_CASE),
        Regex("""\bplease\s+stop\b""", RegexOption.IGNORE_CASE),  // could be barge-in OR human
        Regex("""\blook\s+at\s+this\b""", RegexOption.IGNORE_CASE) // unless context suggests vision intent
    )

    fun strongMatch(text: String): Boolean =
        STRONG.any { it.containsMatchIn(text) }

    fun softMatchCount(text: String): Int =
        SOFT.count { it.containsMatchIn(text) }
}
