package com.jarvis.assistant.core.context

/**
 * SocialContext — lightweight tone + engagement read derived from the last
 * few turns.
 *
 * Mirrors [com.jarvis.assistant.context.Presence] in spirit: a pure value
 * type recomputed on demand, consumed by the system prompt so the LLM can
 * shade tone without Jarvis ever naming the signal out loud.
 *
 * Tone is a coarse read — four buckets are deliberate. WARM when the user
 * has been chatty and relaxed; TERSE when turns are short and clipped;
 * CONCERNED when sentiment cues negative affect; NEUTRAL otherwise.
 *
 * Engagement is a 0f..1f float — higher means the user is actively
 * engaged right now. Computed from [minutesSinceLastTurn] (recency) and
 * [recentTurnCount] (density).
 */
data class SocialContext(
    val tone            : ConversationTone,
    val engagement      : Float,
    val recentTurnCount : Int,
    val minutesSinceLastTurn: Long,
) {
    /** Optional system-prompt line. Returns null when nothing useful to add. */
    fun toPromptFragment(): String? {
        val toneWord = when (tone) {
            ConversationTone.WARM      -> "warm"
            ConversationTone.TERSE     -> "terse"
            ConversationTone.CONCERNED -> "concerned"
            ConversationTone.NEUTRAL   -> return null
        }
        return "Recent tone: $toneWord."
    }

    companion object {
        private const val TERSE_AVG_CHARS   = 12
        private const val WARM_AVG_CHARS    = 60
        private val  CONCERN_WORDS          = setOf(
            "tired", "anxious", "worried", "stressed", "frustrated",
            "sad", "upset", "overwhelmed", "angry", "exhausted"
        )

        /** Neutral default — safe for cold start and tests. */
        val NEUTRAL = SocialContext(
            tone = ConversationTone.NEUTRAL,
            engagement = 0f,
            recentTurnCount = 0,
            minutesSinceLastTurn = Long.MAX_VALUE,
        )

        /**
         * Derive a SocialContext from the last few user turns.
         *
         * @param recentUserTurns Text of the last up-to-5 user turns (newest last).
         * @param minutesSinceLastTurn Minutes since the most recent turn.
         */
        fun from(
            recentUserTurns: List<String>,
            minutesSinceLastTurn: Long,
        ): SocialContext {
            if (recentUserTurns.isEmpty()) return NEUTRAL.copy(
                minutesSinceLastTurn = minutesSinceLastTurn
            )

            val joined = recentUserTurns.joinToString(" ").lowercase()
            val avgLen = recentUserTurns.map { it.trim().length }.average()

            val tone = when {
                CONCERN_WORDS.any { it in joined } -> ConversationTone.CONCERNED
                avgLen <= TERSE_AVG_CHARS          -> ConversationTone.TERSE
                avgLen >= WARM_AVG_CHARS           -> ConversationTone.WARM
                else                               -> ConversationTone.NEUTRAL
            }

            val recencyScore = when {
                minutesSinceLastTurn <= 1L   -> 1f
                minutesSinceLastTurn <= 5L   -> 0.7f
                minutesSinceLastTurn <= 15L  -> 0.4f
                else                         -> 0.1f
            }
            val densityScore = (recentUserTurns.size / 5f).coerceAtMost(1f)
            val engagement = (0.6f * recencyScore + 0.4f * densityScore).coerceIn(0f, 1f)

            return SocialContext(
                tone = tone,
                engagement = engagement,
                recentTurnCount = recentUserTurns.size,
                minutesSinceLastTurn = minutesSinceLastTurn,
            )
        }
    }
}

enum class ConversationTone { NEUTRAL, WARM, TERSE, CONCERNED }
