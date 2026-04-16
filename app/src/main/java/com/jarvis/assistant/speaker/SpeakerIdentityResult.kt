package com.jarvis.assistant.speaker

/**
 * Result of one speaker identification attempt against enrolled voice profiles.
 *
 * Three confidence bands drive all downstream behaviour:
 *
 *   HIGH_CONFIDENCE_MATCH         → greet by name; personal actions allowed
 *   LOW_CONFIDENCE_OR_AMBIGUOUS   → neutral greeting; public actions only
 *   UNKNOWN                       → ask "who's this?"; public actions only
 *
 * The bands are set by [SpeakerEmbeddingEngine.THRESHOLD_HIGH] and
 * [SpeakerEmbeddingEngine.THRESHOLD_LOW]; adjust those constants if the default
 * MFCC-based recognition is too aggressive or too conservative.
 */
data class SpeakerIdentityResult(
    /** Cosine similarity score 0.0–1.0 against the best-matching stored profile. */
    val confidence: Float,
    /** DB row id of the matched [PersonRecord], null when unrecognised. */
    val personId: Long?,
    /** User-facing display name of the matched person, null when unrecognised. */
    val displayName: String?,
    val band: ConfidenceBand
) {
    enum class ConfidenceBand {
        HIGH_CONFIDENCE_MATCH,
        LOW_CONFIDENCE_OR_AMBIGUOUS,
        UNKNOWN
    }

    val isKnown: Boolean get() = personId != null

    companion object {
        /**
         * Used when audio could not be captured (API < 29, mic unavailable, etc.).
         * Treated identically to UNKNOWN for greeting and permission decisions.
         */
        val UNAVAILABLE = SpeakerIdentityResult(0f, null, null, ConfidenceBand.UNKNOWN)
    }
}
