package com.jarvis.assistant.trust

import com.jarvis.assistant.speaker.trust.VoiceTrustState

/**
 * Converts a [TrustContext] into a [TrustScore] by accumulating weighted
 * signals and deriving the most relevant [TrustMode].
 *
 * All logic is stateless and Android-free so it can be exercised in unit
 * tests without mocking.
 */
object TrustSignalEvaluator {

    // ── Signal weights (must sum to ≤ 1.0 when all are active) ──────────────

    private const val W_TRUSTED_VOICE    = 0.30f
    private const val W_OWNER_MODE       = 0.20f
    private const val W_DEVICE_UNLOCKED  = 0.10f
    private const val W_RECENT_SUCCESS   = 0.08f
    private const val W_SESSION_ACTIVE   = 0.08f
    private const val W_HIGH_CONFIDENCE  = 0.08f
    private const val W_HOME_WIFI        = 0.06f
    private const val W_TRUSTED_BT       = 0.05f
    private const val W_HEADPHONES       = 0.03f
    private const val W_NO_CORRECTIONS   = 0.05f
    // Car mode contributes no weight directly; it overrides the TrustMode instead

    /** Baseline score — even with no signals Jarvis assumes the owner is speaking. */
    private const val BASELINE = 0.35f

    fun evaluate(ctx: TrustContext): TrustScore {
        val active = mutableSetOf<TrustSignal>()
        var score = BASELINE

        // Voice trust
        when (ctx.voiceTrust) {
            VoiceTrustState.VOICE_MATCHED, VoiceTrustState.OWNER_TRUSTED -> {
                active += TrustSignal.TRUSTED_VOICE
                active += TrustSignal.OWNER_MODE_ACTIVE
                score += W_TRUSTED_VOICE + W_OWNER_MODE
            }
            VoiceTrustState.OWNER_ASSUMED -> {
                active += TrustSignal.OWNER_MODE_ACTIVE
                score += W_OWNER_MODE
            }
            VoiceTrustState.VOICE_UNKNOWN -> { /* no bonus */ }
            VoiceTrustState.VOICE_MISMATCH -> score -= 0.20f
            VoiceTrustState.REAUTH_REQUIRED -> score -= 0.30f
        }

        if (!ctx.deviceLocked) {
            active += TrustSignal.DEVICE_UNLOCKED
            score += W_DEVICE_UNLOCKED
        }
        if (ctx.sessionActive) {
            active += TrustSignal.SESSION_ACTIVE
            score += W_SESSION_ACTIVE
        }
        if (ctx.recentSuccess) {
            active += TrustSignal.RECENT_SUCCESS
            score += W_RECENT_SUCCESS
        }
        if (ctx.confidenceHigh) {
            active += TrustSignal.HIGH_CONFIDENCE_TRANSCRIPT
            score += W_HIGH_CONFIDENCE
        }
        if (ctx.isHomeWifi) {
            active += TrustSignal.HOME_WIFI
            score += W_HOME_WIFI
        }
        if (ctx.headphonesConnected) {
            active += TrustSignal.HEADPHONES_CONNECTED
            score += W_HEADPHONES
        }
        if (ctx.recentCorrectionCount == 0) {
            active += TrustSignal.NO_RECENT_CORRECTIONS
            score += W_NO_CORRECTIONS
        } else {
            // Each correction slightly reduces trust (caps at -0.15)
            score -= minOf(ctx.recentCorrectionCount * 0.05f, 0.15f)
        }
        if (ctx.isCarMode) {
            active += TrustSignal.CAR_MODE
            // Car mode doesn't raise the score — but it overrides TrustMode
        }

        val clamped = score.coerceIn(0f, 1f)
        val mode    = resolveMode(ctx, clamped, active)

        return TrustScore(value = clamped, mode = mode, activeSignals = active)
    }

    private fun resolveMode(
        ctx: TrustContext,
        score: Float,
        signals: Set<TrustSignal>,
    ): TrustMode = when {
        ctx.isCarMode                                           -> TrustMode.CAR_MODE
        ctx.deviceLocked                                        -> TrustMode.LOCKSCREEN_LIMITED
        ctx.voiceTrust == VoiceTrustState.VOICE_MISMATCH        -> TrustMode.UNKNOWN_SPEAKER
        ctx.headphonesConnected && score >= 0.55f               -> TrustMode.HEADPHONES_PRIVATE
        ctx.isHomeWifi && score >= 0.70f                        -> TrustMode.HOME_TRUSTED
        TrustSignal.TRUSTED_VOICE in signals ||
            TrustSignal.OWNER_MODE_ACTIVE in signals            -> TrustMode.OWNER_TRUSTED
        score >= 0.50f                                          -> TrustMode.LIMITED_TRUST
        else                                                    -> TrustMode.UNKNOWN_SPEAKER
    }
}
