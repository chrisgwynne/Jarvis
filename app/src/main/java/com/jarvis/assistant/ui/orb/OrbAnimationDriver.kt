package com.jarvis.assistant.ui.orb

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import kotlin.math.PI

/**
 * Snapshot of all animated values needed to render the orb in a single frame.
 * Produced by [rememberOrbAnimatedSnapshot] and consumed by [JarvisOrb].
 */
data class OrbAnimatedSnapshot(
    val primaryColor               : Color,
    val glowColor                  : Color,
    val coreAlpha                  : Float,
    /** Current breathing scale multiplier — applied to orbRadius inside Canvas. */
    val orbScale                   : Float,
    val glowAlpha                  : Float,
    val glowRadiusFraction         : Float,
    /** Current rotation angle in degrees — only rendered when [rotationEnabled]. */
    val rotationDegrees            : Float,
    val rotationEnabled            : Boolean,
    val waveformVisible            : Boolean,
    val waveformBaseHeightFraction : Float,
    val waveformMaxHeightFraction  : Float,
    /** Advances 0→2π continuously — drives the ripple pattern rotation. */
    val wavePhase                  : Float,
    /** Synthetic amplitude 0..1 from OrbViewModel. */
    val amplitude                  : Float
)

/**
 * Composable function that maps [visualState] + [amplitude] to a fully-animated
 * [OrbAnimatedSnapshot] ready for the Canvas renderer.
 *
 * Strategy:
 * - Colors / alphas use [animateColorAsState] / [animateFloatAsState] with the
 *   spec's [OrbRenderSpec.enterTransitionMs] so cross-state fades are smooth.
 * - Breathing uses a keyed [rememberInfiniteTransition] so the cadence restarts
 *   precisely when the breathing period changes (i.e. on state transition).
 * - Rotation and wave-phase each have their own keyed InfiniteTransitions.
 */
@Composable
fun rememberOrbAnimatedSnapshot(
    visualState : OrbVisualState,
    amplitude   : Float
): OrbAnimatedSnapshot {

    val spec = OrbRenderSpec.forState(visualState)

    // ── Cross-state colour / alpha transitions ────────────────────────────────
    val primaryColor by animateColorAsState(
        targetValue   = spec.primaryColor,
        animationSpec = tween(spec.enterTransitionMs),
        label         = "orbPrimaryColor"
    )
    val glowColor by animateColorAsState(
        targetValue   = spec.glowColor,
        animationSpec = tween(spec.enterTransitionMs),
        label         = "orbGlowColor"
    )
    val coreAlpha by animateFloatAsState(
        targetValue   = spec.coreAlpha,
        animationSpec = tween(spec.enterTransitionMs),
        label         = "orbCoreAlpha"
    )
    val glowAlpha by animateFloatAsState(
        targetValue   = spec.glowAlpha,
        animationSpec = tween(spec.enterTransitionMs),
        label         = "orbGlowAlpha"
    )

    // ── Breathing — re-keyed on period so new cadence starts fresh ────────────
    // Each OrbRenderSpec uses a unique breathingPeriodMs, so the key always
    // changes together with the rest of the spec on a state transition.
    val orbScale by key(spec.breathingPeriodMs) {
        rememberInfiniteTransition(label = "orbBreathing")
            .animateFloat(
                initialValue  = spec.minScaleFactor,
                targetValue   = spec.maxScaleFactor,
                animationSpec = infiniteRepeatable(
                    animation  = tween(
                        durationMillis = (spec.breathingPeriodMs / 2).coerceAtLeast(100),
                        easing         = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "orbBreathScale"
            )
    }

    // ── Processing arc rotation ───────────────────────────────────────────────
    val rotationDegrees by key(spec.rotationPeriodMs) {
        rememberInfiniteTransition(label = "orbRotation")
            .animateFloat(
                initialValue  = 0f,
                targetValue   = 360f,
                animationSpec = infiniteRepeatable(
                    animation  = tween(spec.rotationPeriodMs, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "orbRotDeg"
            )
    }

    // ── Waveform phase — drives ripple-pattern rotation around the ring ───────
    val wavePhase by key(spec.waveformPhasePeriodMs) {
        rememberInfiniteTransition(label = "orbWavePhase")
            .animateFloat(
                initialValue  = 0f,
                targetValue   = (2.0 * PI).toFloat(),
                animationSpec = infiniteRepeatable(
                    animation  = tween(spec.waveformPhasePeriodMs, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "orbWavePhaseVal"
            )
    }

    return OrbAnimatedSnapshot(
        primaryColor               = primaryColor,
        glowColor                  = glowColor,
        coreAlpha                  = coreAlpha,
        orbScale                   = orbScale,
        glowAlpha                  = glowAlpha,
        glowRadiusFraction         = spec.glowRadiusFraction,
        rotationDegrees            = rotationDegrees,
        rotationEnabled            = spec.rotationEnabled,
        waveformVisible            = spec.waveformVisible,
        waveformBaseHeightFraction = spec.waveformBaseHeightFraction,
        waveformMaxHeightFraction  = spec.waveformMaxHeightFraction,
        wavePhase                  = wavePhase,
        amplitude                  = amplitude
    )
}
