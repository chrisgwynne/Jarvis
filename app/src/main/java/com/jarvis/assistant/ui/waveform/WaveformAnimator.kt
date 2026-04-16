package com.jarvis.assistant.ui.waveform

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.jarvis.assistant.ui.orb.OrbVisualState
import kotlinx.coroutines.isActive

/**
 * A single-frame snapshot of the fully-animated waveform state.
 * All fields are already cross-faded/smoothed — the Canvas consumes
 * this directly without further interpolation.
 */
data class WaveformSnapshot(
    val primaryColor       : Color,
    val glowColor          : Color,
    val glowIntensity      : Float,
    val barCount           : Int,
    val phase              : Float,
    val effectiveAmplitude : Float,
    val spatialFrequency   : Float,
    val harmonicStrength   : Float,
    val maxHeightFraction  : Float
)

/**
 * Drives waveform animation by:
 *   1. Cross-fading visual parameters between [WaveformSpec] instances via
 *      Compose animate*AsState (colour + float values).
 *   2. Advancing [phase] every frame using [withFrameMillis] on the
 *      Compose frame clock — no external timer needed.
 *   3. Smoothing [inputAmplitude] with an exponential low-pass filter
 *      (τ ≈ 80 ms) to remove audio spikes without lag.
 *
 * @param visualState    current [OrbVisualState] — selects the target [WaveformSpec]
 * @param inputAmplitude live mic/TTS signal in 0f..1f; ignored for non-reactive specs
 */
@Composable
fun rememberWaveformSnapshot(
    visualState    : OrbVisualState,
    inputAmplitude : Float
): WaveformSnapshot {

    val spec  = WaveformSpec.forState(visualState)
    val trans = spec.enterTransitionMs

    // ── Compose-animated cross-fades between spec values ─────────────────────
    val primaryColor  by animateColorAsState(spec.primaryColor,       tween(trans), label = "wfColor")
    val glowColor     by animateColorAsState(spec.glowColor,          tween(trans), label = "wfGlow")
    val glowIntensity by animateFloatAsState(spec.glowIntensity,      tween(trans), label = "wfGlowI")
    val baseAmplitude by animateFloatAsState(spec.baseAmplitude,      tween(trans), label = "wfBase")
    val reactivity    by animateFloatAsState(spec.reactivity,         tween(trans), label = "wfReact")
    val phaseSpeed    by animateFloatAsState(spec.phaseSpeed,         tween(trans), label = "wfSpeed")
    val spatialFreq   by animateFloatAsState(spec.spatialFrequency,   tween(trans), label = "wfSFreq")
    val harmonics     by animateFloatAsState(spec.harmonicStrength,   tween(trans), label = "wfHarm")
    val maxHeight     by animateFloatAsState(spec.maxHeightFraction,  tween(trans), label = "wfMaxH")

    // ── Frame-clock ticker for phase + amplitude smoothing ────────────────────
    var phase       by remember { mutableFloatStateOf(0f) }
    var smoothedAmp by remember { mutableFloatStateOf(0f) }

    // rememberUpdatedState lets the loop see the latest animated values
    // without restarting on every recomposition.
    val latestBase       by rememberUpdatedState(baseAmplitude)
    val latestReactivity by rememberUpdatedState(reactivity)
    val latestSpeed      by rememberUpdatedState(phaseSpeed)
    val latestInput      by rememberUpdatedState(inputAmplitude)

    LaunchedEffect(Unit) {
        var lastMs = 0L
        while (isActive) {
            val frameMs = withFrameMillis { it }
            // Cap dt at 64 ms to handle backgrounding / first frame
            val dt = ((frameMs - lastMs).coerceIn(0L, 64L)) / 1000f
            lastMs = frameMs

            // Advance phase; wrap at 100π (~314 radians) to prevent precision loss
            phase = (phase + latestSpeed * dt) % 628f

            // Exponential low-pass: τ ≈ 80 ms
            val target = latestBase + latestReactivity * latestInput
            val alpha  = (dt / 0.08f).coerceIn(0f, 1f)
            smoothedAmp += (target - smoothedAmp) * alpha
        }
    }

    return WaveformSnapshot(
        primaryColor       = primaryColor,
        glowColor          = glowColor,
        glowIntensity      = glowIntensity,
        barCount           = spec.barCount,
        phase              = phase,
        effectiveAmplitude = smoothedAmp,
        spatialFrequency   = spatialFreq,
        harmonicStrength   = harmonics,
        maxHeightFraction  = maxHeight
    )
}
