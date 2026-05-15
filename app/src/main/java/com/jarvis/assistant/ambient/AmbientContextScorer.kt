package com.jarvis.assistant.ambient

/**
 * AmbientContextScorer — decides whether a given ambient context is
 * strong enough to emit a proactive nudge, and at what level.
 *
 * This is a policy class, not an ML model.  Its job is to translate
 * raw signal strength into the four dispatch levels so triggers can
 * express a score without hardcoding delivery decisions.
 */
object AmbientContextScorer {

    enum class Level {
        /** Not surfaced — log only. */
        NONE,
        /** Post a silent notification. */
        PASSIVE,
        /** Post a notification and speak if conditions allow. */
        NOTIFY,
        /** Speak immediately when conditions allow. */
        SPEAK,
    }

    /**
     * Score a raw confidence value against the user's settings.
     *
     * @param confidence     Signal confidence [0, 1].
     * @param minToSpeak     User's configured minimum-to-speak threshold.
     * @param dismissalCount How many times this pattern has been dismissed.
     * @param isUncertain    True if the signal source itself is uncertain
     *                       (e.g. location inferred from SSID, not GPS).
     */
    fun score(
        confidence: Float,
        minToSpeak: Float = 0.65f,
        dismissalCount: Int = 0,
        isUncertain: Boolean = false,
    ): Level {
        // Each dismissal drops effective confidence by 10 %.
        val effective = (confidence - dismissalCount * 0.10f).coerceAtLeast(0f)

        return when {
            effective < 0.30f           -> Level.NONE
            isUncertain || effective < minToSpeak -> Level.NOTIFY
            effective >= minToSpeak     -> Level.SPEAK
            else                        -> Level.PASSIVE
        }
    }

    /**
     * Translate an ambient [Level] to the urgency + annoyanceCost pair
     * expected by the [com.jarvis.assistant.core.decisions.Candidate] builder.
     */
    fun toScores(level: Level): Pair<Float, Float> = when (level) {
        Level.NONE    -> 0.0f to 0.9f
        Level.PASSIVE -> 0.3f to 0.5f
        Level.NOTIFY  -> 0.5f to 0.40f
        Level.SPEAK   -> 0.7f to 0.25f
    }
}
