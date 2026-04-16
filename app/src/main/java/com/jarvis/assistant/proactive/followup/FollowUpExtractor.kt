package com.jarvis.assistant.proactive.followup

/**
 * FollowUpExtractor — creates a [PendingFollowUp] from a PERSONAL_UPDATE utterance
 * when the content is worth checking in on later.
 *
 * Returns null for generic updates that don't warrant a follow-up
 * (e.g. "I prefer dark mode", "I hate Mondays").
 *
 * Only creates follow-ups for:
 *   - Future events / plans with a clear time horizon
 *   - Stress or difficulty signals worth a wellbeing check-in
 */
object FollowUpExtractor {

    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS  = 86_400_000L

    fun extract(input: String): PendingFollowUp? {
        val lower = input.lowercase().trim()
        val now   = System.currentTimeMillis()

        // ── Future named events ────────────────────────────────────────────
        // "I'm going on a cruise tomorrow", "big meeting on Friday"
        NAMED_EVENT_PATTERN.find(lower)?.let { m ->
            val event     = (m.groupValues[1].takeIf { it.isNotBlank() } ?: "that").trim()
            val timeRef   = m.groupValues[2].trim()
            val dueOffset = offsetFromTimeRef(timeRef, default = DAY_MS + 12 * HOUR_MS)
            return PendingFollowUp(
                type           = PendingFollowUp.TYPE_EVENT,
                topic          = event,
                promptTemplate = "How did ${article(event)} $event go?",
                dueAt          = now + dueOffset,
                expiresAt      = now + dueOffset + 3 * DAY_MS
            )
        }

        // "I'm flying to London on Friday", "heading to Dubai tomorrow"
        GOING_TO_PATTERN.find(lower)?.let { m ->
            val destination = m.groupValues[1].trim().trimEnd(',', '.')
            val timeRef     = m.groupValues[2].trim()
            val dueOffset   = offsetFromTimeRef(timeRef, default = DAY_MS + 12 * HOUR_MS)
            if (destination.length < 3) return@let
            return PendingFollowUp(
                type           = PendingFollowUp.TYPE_EVENT,
                topic          = destination,
                promptTemplate = "How was $destination?",
                dueAt          = now + dueOffset,
                expiresAt      = now + dueOffset + 3 * DAY_MS
            )
        }

        // ── Stress / difficulty — wellbeing check-in ───────────────────────
        if (STRESS_PATTERN.containsMatchIn(lower)) {
            return PendingFollowUp(
                type           = PendingFollowUp.TYPE_WELLBEING,
                topic          = "things",
                promptTemplate = WELLBEING_CHECK_INS.random(),
                dueAt          = now + 8 * HOUR_MS,
                expiresAt      = now + DAY_MS
            )
        }

        return null
    }

    // ── Patterns ──────────────────────────────────────────────────────────────

    // Catches: "going on a cruise tomorrow", "big meeting on Monday", "job interview next week",
    //          "flight on Friday", "appointment tomorrow"
    private val NAMED_EVENT_PATTERN = Regex(
        """(?:i(?:'m| am) (?:going on|having|doing)|big|important|huge|my)\s+(?:a |an )?""" +
        """(cruise|trip|flight|meeting|interview|appointment|exam|test|date|holiday|""" +
        """wedding|birthday|party|presentation|surgery|procedure|drive|journey|match|game)""" +
        """[^.]*?\s+(tomorrow|today|tonight|next week|on \w+day|this weekend|in a few days|""" +
        """in \d+ days?|monday|tuesday|wednesday|thursday|friday|saturday|sunday)""",
        RegexOption.IGNORE_CASE
    )

    // Catches: "i'm flying to London tomorrow", "heading to Dubai on Friday"
    private val GOING_TO_PATTERN = Regex(
        """i(?:'m| am) (?:going to|flying to|travelling to|traveling to|heading to|""" +
        """visiting|driving to|taking a train to)\s+([a-z][a-z\s]+?)""" +
        """\s*(tomorrow|today|tonight|next week|on \w+day|this weekend|in a few days)?""" +
        """(?:\s*$|[.,])""",
        RegexOption.IGNORE_CASE
    )

    private val STRESS_PATTERN = Regex(
        """(?:stressed out|really stressed|so stressed|exhausted|burnt out|burn out|""" +
        """completely overwhelmed|losing my mind|at my wit|horrible day|terrible day|""" +
        """worst day)""",
        RegexOption.IGNORE_CASE
    )

    private val WELLBEING_CHECK_INS = listOf(
        "Feeling any better now?",
        "Hope things have settled down a bit.",
        "How are you doing now?",
        "Any better than earlier?"
    )

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun offsetFromTimeRef(ref: String, default: Long): Long = when {
        ref.contains("tonight") || ref.contains("today")          -> 6 * HOUR_MS
        ref.contains("tomorrow")                                   -> DAY_MS + 12 * HOUR_MS
        ref.contains("this weekend")                               -> 2 * DAY_MS
        ref.contains("next week") || ref.contains("in a few days") -> 8 * DAY_MS
        ref.matches(Regex("""in (\d+) days?"""))                   -> {
            val days = ref.filter { it.isDigit() }.toLongOrNull() ?: 2
            (days + 1) * DAY_MS
        }
        ref.isNotBlank() /* day name */ -> DAY_MS + 12 * HOUR_MS  // assume within ~a week
        else -> default
    }

    /** Adds "the" or "a/an" naturally in front of an event word. */
    private fun article(word: String): String = when (word) {
        "cruise", "flight", "trip", "interview", "exam", "test",
        "appointment", "surgery", "presentation", "journey", "drive" -> "the"
        else -> "that"
    }
}
