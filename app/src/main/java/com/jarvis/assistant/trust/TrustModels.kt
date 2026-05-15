package com.jarvis.assistant.trust

import com.jarvis.assistant.speaker.trust.VoiceTrustState

/**
 * Contextual mode describing who is using the device and in what environment.
 * Resolved by [TrustSignalEvaluator] from a [TrustContext].
 */
enum class TrustMode {
    /** Owner confirmed by voice match or explicit session start. Full autonomy. */
    OWNER_TRUSTED,

    /** Speaker not recognised; restrict actions that affect other people. */
    UNKNOWN_SPEAKER,

    /** Speaker recognition inconclusive; mild restrictions on HIGH-risk actions. */
    LIMITED_TRUST,

    /** Screen locked; restrict sensitive reads and sends. */
    LOCKSCREEN_LIMITED,

    /** Driving; minimise confirmations, voice-first, allow nav+messaging autonomy. */
    CAR_MODE,

    /** Headphones connected; private-mode readouts allowed; messaging autonomy. */
    HEADPHONES_PRIVATE,

    /** Home Wi-Fi present; ambient context suggests safe environment. */
    HOME_TRUSTED,
}

/**
 * Individual trust signal contributing to a [TrustScore].
 */
enum class TrustSignal {
    TRUSTED_VOICE,
    OWNER_MODE_ACTIVE,
    DEVICE_UNLOCKED,
    RECENT_SUCCESS,          // successful interaction within last 30 s
    TRUSTED_BLUETOOTH,
    HOME_WIFI,
    SESSION_ACTIVE,
    HIGH_CONFIDENCE_TRANSCRIPT,
    CAR_MODE,
    HEADPHONES_CONNECTED,
    NO_RECENT_CORRECTIONS,   // user hasn't corrected Jarvis in the last 5 turns
}

/**
 * Composite trust score computed by [TrustSignalEvaluator].
 *
 * @param value        0.0 (no trust) → 1.0 (full trust)
 * @param mode         resolved [TrustMode] for this context
 * @param activeSignals which signals contributed to the score
 */
data class TrustScore(
    val value: Float,
    val mode: TrustMode,
    val activeSignals: Set<TrustSignal> = emptySet(),
) {
    val isHighTrust: Boolean get() = value >= 0.75f
    val isMediumTrust: Boolean get() = value >= 0.55f
}

/**
 * Bundle of live signals passed to [TrustSignalEvaluator].
 * All fields are optional/defaulted so callers only supply what they have.
 */
data class TrustContext(
    val voiceTrust: VoiceTrustState = VoiceTrustState.OWNER_ASSUMED,
    val sessionActive: Boolean = false,
    val deviceLocked: Boolean = false,
    val isCarMode: Boolean = false,
    val isHomeWifi: Boolean = false,
    val headphonesConnected: Boolean = false,
    val confidenceHigh: Boolean = false,     // transcript confidence tier == HIGH
    val recentSuccess: Boolean = false,      // tool ran successfully < 30 s ago
    val recentCorrectionCount: Int = 0,
)
