package com.jarvis.assistant.ui.orb

import androidx.compose.ui.graphics.Color

/**
 * Immutable description of how the orb should look in a given visual state.
 *
 * All animation targets (colors, alphas, scales, periods) live here.
 * OrbAnimationDriver reads these values and drives Compose animated values
 * towards the current spec whenever it changes.
 */
data class OrbRenderSpec(
    /** Orb core / primary colour. */
    val primaryColor               : Color,
    /** Outer glow / ring colour (usually matches primary). */
    val glowColor                  : Color,
    /** Alpha of the inner radial-gradient core fill. */
    val coreAlpha                  : Float,
    /** Visual diameter of the orb in dp. */
    val orbSizeDp                  : Float,
    /** Minimum breathing scale (compressed phase). */
    val minScaleFactor             : Float,
    /** Maximum breathing scale (expanded phase). */
    val maxScaleFactor             : Float,
    /** Glow radius = orbRadius × glowRadiusFraction. */
    val glowRadiusFraction         : Float,
    /** Alpha of the atmospheric glow. */
    val glowAlpha                  : Float,
    /** Whether the waveform halo is drawn. */
    val waveformVisible            : Boolean,
    /** Minimum waveform bar height as fraction of orbRadius. */
    val waveformBaseHeightFraction : Float,
    /** Maximum additional waveform bar height as fraction of orbRadius. */
    val waveformMaxHeightFraction  : Float,
    /** Full breathing-cycle period in ms (min→max→min). */
    val breathingPeriodMs          : Int,
    /** Period for waveform phase ripple rotation in ms. */
    val waveformPhasePeriodMs      : Int,
    /** Whether the spinning processing arc is rendered. */
    val rotationEnabled            : Boolean,
    /** Period for one full arc rotation in ms. */
    val rotationPeriodMs           : Int,
    /** Cross-fade duration when entering this spec from another state. */
    val enterTransitionMs          : Int
) {
    companion object {

        // ── Palette ───────────────────────────────────────────────────────────
        private val Cyan      = Color(0xFF00BCD4)
        private val Green     = Color(0xFF00E676)
        private val Amber     = Color(0xFFFFAB40)
        private val Purple    = Color(0xFFCE93D8)
        private val Red       = Color(0xFFFF5252)
        private val White     = Color(0xFFFFFFFF)
        private val DimGrey   = Color(0xFF222233)
        private val SlateGrey = Color(0xFF78909C)
        private val DarkSlate = Color(0xFF546E7A)

        // ── State catalogue ───────────────────────────────────────────────────

        val DORMANT = OrbRenderSpec(
            primaryColor = DimGrey, glowColor = DimGrey,
            coreAlpha = 0.25f, orbSizeDp = 180f,
            minScaleFactor = 0.97f, maxScaleFactor = 1.00f,
            glowRadiusFraction = 1.4f, glowAlpha = 0.04f,
            waveformVisible = false,
            waveformBaseHeightFraction = 0f, waveformMaxHeightFraction = 0f,
            breathingPeriodMs = 5000, waveformPhasePeriodMs = 2000,
            rotationEnabled = false, rotationPeriodMs = 3000,
            enterTransitionMs = 600
        )

        val WAKE_LISTENING = OrbRenderSpec(
            primaryColor = Cyan, glowColor = Cyan,
            coreAlpha = 0.40f, orbSizeDp = 180f,
            minScaleFactor = 0.97f, maxScaleFactor = 1.03f,
            glowRadiusFraction = 1.8f, glowAlpha = 0.14f,
            waveformVisible = false,
            waveformBaseHeightFraction = 0f, waveformMaxHeightFraction = 0f,
            breathingPeriodMs = 3200, waveformPhasePeriodMs = 2000,
            rotationEnabled = false, rotationPeriodMs = 3000,
            enterTransitionMs = 400
        )

        val ACTIVATING = OrbRenderSpec(
            primaryColor = White, glowColor = White,
            coreAlpha = 0.85f, orbSizeDp = 180f,
            minScaleFactor = 1.00f, maxScaleFactor = 1.15f,
            glowRadiusFraction = 2.0f, glowAlpha = 0.40f,
            waveformVisible = false,
            waveformBaseHeightFraction = 0f, waveformMaxHeightFraction = 0f,
            breathingPeriodMs = 400, waveformPhasePeriodMs = 2000,
            rotationEnabled = false, rotationPeriodMs = 3000,
            enterTransitionMs = 80
        )

        val LISTENING = OrbRenderSpec(
            primaryColor = Green, glowColor = Green,
            coreAlpha = 0.55f, orbSizeDp = 180f,
            minScaleFactor = 0.90f, maxScaleFactor = 1.10f,
            glowRadiusFraction = 1.65f, glowAlpha = 0.20f,
            waveformVisible = true,
            waveformBaseHeightFraction = 0.04f, waveformMaxHeightFraction = 0.13f,
            breathingPeriodMs = 1400, waveformPhasePeriodMs = 2500,
            rotationEnabled = false, rotationPeriodMs = 3000,
            enterTransitionMs = 300
        )

        val PROCESSING = OrbRenderSpec(
            primaryColor = Amber, glowColor = Amber,
            coreAlpha = 0.60f, orbSizeDp = 180f,
            minScaleFactor = 0.97f, maxScaleFactor = 1.05f,
            glowRadiusFraction = 1.70f, glowAlpha = 0.22f,
            waveformVisible = false,
            waveformBaseHeightFraction = 0f, waveformMaxHeightFraction = 0f,
            breathingPeriodMs = 2000, waveformPhasePeriodMs = 2000,
            rotationEnabled = true, rotationPeriodMs = 2000,
            enterTransitionMs = 250
        )

        val SPEAKING = OrbRenderSpec(
            primaryColor = Purple, glowColor = Purple,
            coreAlpha = 0.65f, orbSizeDp = 180f,
            minScaleFactor = 0.85f, maxScaleFactor = 1.15f,
            glowRadiusFraction = 1.90f, glowAlpha = 0.28f,
            waveformVisible = true,
            waveformBaseHeightFraction = 0.05f, waveformMaxHeightFraction = 0.20f,
            breathingPeriodMs = 700, waveformPhasePeriodMs = 1000,
            rotationEnabled = false, rotationPeriodMs = 3000,
            enterTransitionMs = 200
        )

        val INTERRUPTED = OrbRenderSpec(
            primaryColor = Red, glowColor = Red,
            coreAlpha = 0.75f, orbSizeDp = 180f,
            minScaleFactor = 1.05f, maxScaleFactor = 1.12f,
            glowRadiusFraction = 1.75f, glowAlpha = 0.32f,
            waveformVisible = false,
            waveformBaseHeightFraction = 0f, waveformMaxHeightFraction = 0f,
            breathingPeriodMs = 600, waveformPhasePeriodMs = 2000,
            rotationEnabled = false, rotationPeriodMs = 3000,
            enterTransitionMs = 60
        )

        val SILENCING = OrbRenderSpec(
            primaryColor = Cyan, glowColor = Cyan,
            coreAlpha = 0.18f, orbSizeDp = 180f,
            minScaleFactor = 0.92f, maxScaleFactor = 0.97f,
            glowRadiusFraction = 1.40f, glowAlpha = 0.05f,
            waveformVisible = false,
            waveformBaseHeightFraction = 0f, waveformMaxHeightFraction = 0f,
            breathingPeriodMs = 3000, waveformPhasePeriodMs = 2000,
            rotationEnabled = false, rotationPeriodMs = 3000,
            enterTransitionMs = 500
        )

        val DEGRADED = OrbRenderSpec(
            primaryColor = SlateGrey, glowColor = SlateGrey,
            coreAlpha = 0.38f, orbSizeDp = 180f,
            minScaleFactor = 0.96f, maxScaleFactor = 1.02f,
            glowRadiusFraction = 1.40f, glowAlpha = 0.07f,
            waveformVisible = false,
            waveformBaseHeightFraction = 0f, waveformMaxHeightFraction = 0f,
            breathingPeriodMs = 4000, waveformPhasePeriodMs = 2000,
            rotationEnabled = false, rotationPeriodMs = 3000,
            enterTransitionMs = 400
        )

        val MIC_BLOCKED = OrbRenderSpec(
            primaryColor = DarkSlate, glowColor = DarkSlate,
            coreAlpha = 0.28f, orbSizeDp = 180f,
            minScaleFactor = 1.0f, maxScaleFactor = 1.0f,   // frozen — no breathing
            glowRadiusFraction = 1.30f, glowAlpha = 0.04f,
            waveformVisible = false,
            waveformBaseHeightFraction = 0f, waveformMaxHeightFraction = 0f,
            breathingPeriodMs = 8000, waveformPhasePeriodMs = 2000,
            rotationEnabled = false, rotationPeriodMs = 3000,
            enterTransitionMs = 400
        )

        fun forState(state: OrbVisualState): OrbRenderSpec = when (state) {
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
