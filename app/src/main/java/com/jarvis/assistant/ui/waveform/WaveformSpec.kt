package com.jarvis.assistant.ui.waveform

import androidx.compose.ui.graphics.Color
import com.jarvis.assistant.ui.orb.OrbVisualState

/**
 * Immutable visual parameters that define the waveform's appearance for one
 * assistant state.  [WaveformAnimator] cross-fades between specs on state change.
 *
 * PARAMETER GUIDE
 * ───────────────
 * baseAmplitude    — the waveform height when no external signal is present.
 *                    Produces the idle "breathing" animation.
 * reactivity       — gain applied to the external amplitude before adding to
 *                    baseAmplitude.  0 = purely synthetic, 1 = fully reactive.
 * phaseSpeed       — radians/second the wave pattern scrolls horizontally.
 *                    Higher = faster-moving wave.
 * spatialFrequency — number of full wave cycles visible across the bar span.
 *                    Higher = more peaks visible at once.
 * harmonicStrength — weight of the second harmonic relative to the first.
 *                    Adds organic variation; 0 = pure sine, 1 = equal-weight harmonics.
 * maxHeightFraction— maximum bar half-height as a fraction of canvas height.
 *                    Bars extend this far both above and below the centre line at peak.
 */
data class WaveformSpec(
    val primaryColor      : Color,
    val glowColor         : Color,
    val glowIntensity     : Float,
    val barCount          : Int,
    val baseAmplitude     : Float,
    val reactivity        : Float,
    val phaseSpeed        : Float,
    val spatialFrequency  : Float,
    val harmonicStrength  : Float,
    val maxHeightFraction : Float,
    val enterTransitionMs : Int
) {
    companion object {

        private val Cyan      = Color(0xFF00BCD4)
        private val Green     = Color(0xFF00E676)
        private val Amber     = Color(0xFFFFAB40)
        private val Purple    = Color(0xFFCE93D8)
        private val Red       = Color(0xFFFF5252)
        private val White     = Color(0xFFFFFFFF)
        private val SlateGrey = Color(0xFF78909C)
        private val DimGrey   = Color(0xFF1C1C2E)
        private val DarkSlate = Color(0xFF546E7A)

        // ── Service stopped — barely visible flatline ─────────────────────────
        val DORMANT = WaveformSpec(
            primaryColor      = DimGrey,
            glowColor         = DimGrey,
            glowIntensity     = 0.04f,
            barCount          = 60,
            baseAmplitude     = 0.07f,
            reactivity        = 0f,
            phaseSpeed        = 0.5f,
            spatialFrequency  = 2.0f,
            harmonicStrength  = 0.20f,
            maxHeightFraction = 0.14f,
            enterTransitionMs = 800
        )

        // ── Wake-word loop active — soft cyan breath ──────────────────────────
        val WAKE_LISTENING = WaveformSpec(
            primaryColor      = Cyan,
            glowColor         = Cyan,
            glowIntensity     = 0.14f,
            barCount          = 64,
            baseAmplitude     = 0.18f,
            reactivity        = 0f,
            phaseSpeed        = 1.4f,
            spatialFrequency  = 2.5f,
            harmonicStrength  = 0.30f,
            maxHeightFraction = 0.32f,
            enterTransitionMs = 500
        )

        // ── Wake phrase detected — brief white burst ──────────────────────────
        val ACTIVATING = WaveformSpec(
            primaryColor      = White,
            glowColor         = White,
            glowIntensity     = 0.60f,
            barCount          = 64,
            baseAmplitude     = 0.78f,
            reactivity        = 0f,
            phaseSpeed        = 8.0f,
            spatialFrequency  = 3.5f,
            harmonicStrength  = 0.50f,
            maxHeightFraction = 0.58f,
            enterTransitionMs = 80
        )

        // ── Mic open — reactive to microphone amplitude ───────────────────────
        val LISTENING = WaveformSpec(
            primaryColor      = Green,
            glowColor         = Green,
            glowIntensity     = 0.22f,
            barCount          = 80,
            baseAmplitude     = 0.20f,
            reactivity        = 0.80f,
            phaseSpeed        = 3.5f,
            spatialFrequency  = 3.0f,
            harmonicStrength  = 0.45f,
            maxHeightFraction = 0.62f,
            enterTransitionMs = 300
        )

        // ── LLM / tool running — slow flowing amber ───────────────────────────
        val PROCESSING = WaveformSpec(
            primaryColor      = Amber,
            glowColor         = Amber,
            glowIntensity     = 0.18f,
            barCount          = 60,
            baseAmplitude     = 0.32f,
            reactivity        = 0f,
            phaseSpeed        = 2.2f,
            spatialFrequency  = 2.0f,
            harmonicStrength  = 0.60f,
            maxHeightFraction = 0.42f,
            enterTransitionMs = 300
        )

        // ── TTS playing — reactive to TTS amplitude ───────────────────────────
        val SPEAKING = WaveformSpec(
            primaryColor      = Purple,
            glowColor         = Purple,
            glowIntensity     = 0.30f,
            barCount          = 80,
            baseAmplitude     = 0.28f,
            reactivity        = 0.72f,
            phaseSpeed        = 5.0f,
            spatialFrequency  = 3.5f,
            harmonicStrength  = 0.55f,
            maxHeightFraction = 0.70f,
            enterTransitionMs = 200
        )

        // ── Barge-in — red flash then quick decay ─────────────────────────────
        val INTERRUPTED = WaveformSpec(
            primaryColor      = Red,
            glowColor         = Red,
            glowIntensity     = 0.38f,
            barCount          = 64,
            baseAmplitude     = 0.65f,
            reactivity        = 0f,
            phaseSpeed        = 9.0f,
            spatialFrequency  = 4.0f,
            harmonicStrength  = 0.70f,
            maxHeightFraction = 0.55f,
            enterTransitionMs = 60
        )

        // ── Silence commanded — cyan fading to flatline ───────────────────────
        val SILENCING = WaveformSpec(
            primaryColor      = Cyan,
            glowColor         = Cyan,
            glowIntensity     = 0.06f,
            barCount          = 60,
            baseAmplitude     = 0.09f,
            reactivity        = 0f,
            phaseSpeed        = 0.8f,
            spatialFrequency  = 2.0f,
            harmonicStrength  = 0.20f,
            maxHeightFraction = 0.16f,
            enterTransitionMs = 600
        )

        // ── Offline / cloud unreachable — grey slow pulse ─────────────────────
        val DEGRADED = WaveformSpec(
            primaryColor      = SlateGrey,
            glowColor         = SlateGrey,
            glowIntensity     = 0.08f,
            barCount          = 60,
            baseAmplitude     = 0.14f,
            reactivity        = 0f,
            phaseSpeed        = 0.7f,
            spatialFrequency  = 1.5f,
            harmonicStrength  = 0.20f,
            maxHeightFraction = 0.22f,
            enterTransitionMs = 500
        )

        // ── Mic held by phone call — nearly flat ──────────────────────────────
        val MIC_BLOCKED = WaveformSpec(
            primaryColor      = DarkSlate,
            glowColor         = DarkSlate,
            glowIntensity     = 0.03f,
            barCount          = 60,
            baseAmplitude     = 0.05f,
            reactivity        = 0f,
            phaseSpeed        = 0.3f,
            spatialFrequency  = 1.5f,
            harmonicStrength  = 0.10f,
            maxHeightFraction = 0.10f,
            enterTransitionMs = 500
        )

        fun forState(state: OrbVisualState): WaveformSpec = when (state) {
            is OrbVisualState.Dormant       -> DORMANT
            is OrbVisualState.WakeListening -> WAKE_LISTENING
            is OrbVisualState.Activating    -> ACTIVATING
            is OrbVisualState.Listening     -> LISTENING
            is OrbVisualState.Processing    -> PROCESSING
            is OrbVisualState.Speaking      -> SPEAKING
            is OrbVisualState.Interrupted   -> INTERRUPTED
            is OrbVisualState.Silencing     -> SILENCING
            is OrbVisualState.Degraded      -> DEGRADED
            is OrbVisualState.MicBlocked    -> MIC_BLOCKED
        }
    }
}
