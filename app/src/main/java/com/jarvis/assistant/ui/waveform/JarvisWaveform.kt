package com.jarvis.assistant.ui.waveform

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.orb.OrbVisualState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * JarvisWaveform — Canvas-drawn animated waveform for the Jarvis main screen.
 *
 * Rendering layers (back to front):
 *   1. Central radial glow — soft halo at canvas mid-point
 *   2. Vertical waveform bars — amplitude-driven, sine-windowed at edges
 *   3. Horizontal accent line — thin gradient rule at the centre line
 *
 * All visual parameters are driven by [WaveformSpec] through [rememberWaveformSnapshot],
 * which cross-fades smoothly between states and advances phase each frame.
 *
 * @param visualState    current assistant state — selects bar colour and motion profile
 * @param amplitude      live mic/TTS signal 0f..1f; only applied in reactive states
 * @param modifier       layout modifier; fill the parent width and set a height
 */
@Composable
fun JarvisWaveform(
    visualState : OrbVisualState,
    amplitude   : Float,
    modifier    : Modifier = Modifier
) {
    val snap = rememberWaveformSnapshot(visualState, amplitude)

    Canvas(modifier = modifier) {
        val w  = size.width
        val h  = size.height
        val cx = w / 2f
        val cy = h / 2f

        // ── Layer 1: Central radial glow ──────────────────────────────────────
        // Glow spans 55% of canvas width so it bleeds subtly behind the bars.
        val glowRadius = w * 0.55f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    snap.glowColor.copy(alpha = snap.glowIntensity),
                    snap.glowColor.copy(alpha = snap.glowIntensity * 0.35f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = glowRadius
            ),
            radius = glowRadius,
            center = Offset(cx, cy)
        )

        // ── Layer 2: Waveform bars ─────────────────────────────────────────────
        // Bars span 88% of canvas width; each bar occupies a fixed slot.
        val barCount    = snap.barCount
        val totalSpan   = w * 0.88f
        val slotW       = totalSpan / barCount
        val filledW     = (slotW * 0.58f).coerceAtLeast(1f)
        val spanStart   = cx - totalSpan / 2f
        val maxHalfH    = h * snap.maxHeightFraction

        for (i in 0 until barCount) {
            // Normalised position 0..1 across the bar array
            val t = if (barCount > 1) i.toFloat() / (barCount - 1) else 0.5f

            // ── Wave shape ────────────────────────────────────────────────────
            // Primary harmonic: absolute sine so all bars are above/below centre
            val spatialAngle = t * snap.spatialFrequency * (2f * PI.toFloat())
            val h1 = abs(sin(snap.phase + spatialAngle))

            // Second harmonic for organic variation (phase doubled, freq doubled)
            val h2 = abs(sin(snap.phase * 2f + spatialAngle * 2f))

            // Blend harmonics then normalise so peak ≈ 1.0
            val rawH = (h1 + snap.harmonicStrength * h2) / (1f + snap.harmonicStrength)

            // Sine envelope tapers bars at both edges (peak at centre, ~0 at edges)
            val window = sin(t * PI.toFloat()).coerceAtLeast(0f)

            val barHalfH = (maxHalfH * rawH * snap.effectiveAmplitude * window)
                .coerceAtLeast(1.5f)  // minimum 3dp visible when near-silent

            // ── Colour ────────────────────────────────────────────────────────
            // Bars in the centre of the array (high window) are more opaque.
            val alpha    = (0.45f + 0.55f * window).coerceIn(0.1f, 1f)
            val barColor = snap.primaryColor.copy(alpha = alpha)

            val barLeft = spanStart + i * slotW + (slotW - filledW) / 2f
            drawRect(
                color    = barColor,
                topLeft  = Offset(barLeft, cy - barHalfH),
                size     = Size(filledW, barHalfH * 2f)
            )
        }

        // ── Layer 3: Accent centre line ───────────────────────────────────────
        // A single-pixel horizontal rule that fades to transparent at both edges.
        val lineW  = w * 0.72f
        val lineX  = cx - lineW / 2f
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    snap.primaryColor.copy(alpha = 0.25f),
                    snap.primaryColor.copy(alpha = 0.25f),
                    Color.Transparent
                ),
                startX = lineX,
                endX   = lineX + lineW
            ),
            topLeft = Offset(lineX, cy - 0.5f),
            size    = Size(lineW, 1.dp.toPx())
        )
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF08080F, widthDp = 360, heightDp = 200)
@Composable
private fun PreviewDormant() {
    JarvisWaveform(
        visualState = OrbVisualState.Dormant,
        amplitude   = 0f,
        modifier    = Modifier.fillMaxWidth().height(160.dp)
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF08080F, widthDp = 360, heightDp = 200)
@Composable
private fun PreviewListening() {
    JarvisWaveform(
        visualState = OrbVisualState.Listening(amplitude = 0.6f),
        amplitude   = 0.6f,
        modifier    = Modifier.fillMaxWidth().height(160.dp)
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF08080F, widthDp = 360, heightDp = 200)
@Composable
private fun PreviewSpeaking() {
    JarvisWaveform(
        visualState = OrbVisualState.Speaking(amplitude = 0.5f),
        amplitude   = 0.5f,
        modifier    = Modifier.fillMaxWidth().height(160.dp)
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF08080F, widthDp = 360, heightDp = 200)
@Composable
private fun PreviewProcessing() {
    JarvisWaveform(
        visualState = OrbVisualState.Processing(),
        amplitude   = 0f,
        modifier    = Modifier.fillMaxWidth().height(160.dp)
    )
}
