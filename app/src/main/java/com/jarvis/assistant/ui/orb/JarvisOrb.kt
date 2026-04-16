package com.jarvis.assistant.ui.orb

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sin

/**
 * Premium animated orb composable for the Jarvis main screen.
 *
 * The canvas is sized at 2× [orbDiameterDp] so the atmospheric glow has
 * room to bleed beyond the orb boundary without clipping.  The layout
 * footprint is therefore square at [orbDiameterDp] × 2.
 *
 * Rendering layers (back to front):
 *  1. Atmospheric glow  — large radial gradient behind the orb
 *  2. Field rings       — 3 concentric fading ring strokes
 *  3. Waveform halo     — radial bar-chart around the orb edge (waveformVisible states)
 *  4. Processing arcs   — two rotating arc segments (Processing state only)
 *  5. Orb core          — radial-gradient filled circle
 *  6. Specular highlight — small bright spot in upper-left quadrant
 */
@Composable
fun JarvisOrb(
    visualState   : OrbVisualState,
    amplitude     : Float,
    orbDiameterDp : Dp       = 180.dp,
    modifier      : Modifier = Modifier
) {
    val snapshot = rememberOrbAnimatedSnapshot(visualState, amplitude)

    // Canvas is 2× orb diameter — gives glow room outside the orb boundary
    Canvas(modifier = modifier.size(orbDiameterDp * 2)) {

        val orbRadius = orbDiameterDp.toPx() / 2f
        val cx = size.width  / 2f
        val cy = size.height / 2f
        val center = Offset(cx, cy)

        // Apply breathing scale to the orb radius only (not the glow radius).
        // The glow stays at a fixed fraction so it doesn't pulse with the orb.
        val scaledOrbRadius = orbRadius * snapshot.orbScale

        // ── 1. Atmospheric glow ───────────────────────────────────────────────
        val glowRadius = orbRadius * snapshot.glowRadiusFraction
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    snapshot.glowColor.copy(alpha = snapshot.glowAlpha * 0.70f),
                    snapshot.glowColor.copy(alpha = snapshot.glowAlpha * 0.25f),
                    Color.Transparent
                ),
                center = center,
                radius = glowRadius
            ),
            radius = glowRadius,
            center = center
        )

        // ── 2. Field rings (3 concentric, fading outward) ─────────────────────
        val ringStroke = Stroke(width = 1.2.dp.toPx())
        for (i in 0 until 3) {
            val ringRadius = scaledOrbRadius * (1.06f + i * 0.11f)
            val ringAlpha  = snapshot.glowAlpha * (0.55f - i * 0.15f)
            if (ringAlpha > 0.01f) {
                drawCircle(
                    color  = snapshot.glowColor.copy(alpha = ringAlpha),
                    radius = ringRadius,
                    center = center,
                    style  = ringStroke
                )
            }
        }

        // ── 3. Waveform halo ──────────────────────────────────────────────────
        if (snapshot.waveformVisible && snapshot.amplitude > 0.01f) {
            val numBars   = 72
            val barBase   = orbRadius * snapshot.waveformBaseHeightFraction
            val barMax    = orbRadius * snapshot.waveformMaxHeightFraction
            val barWidth  = 2.5.dp.toPx()
            // Edge of the scaled orb, slightly offset outward so bars don't overlap core
            val edgeR     = scaledOrbRadius + 3.dp.toPx()

            for (i in 0 until numBars) {
                val angle   = (2.0 * PI * i / numBars - PI / 2).toFloat()
                // Ripple pattern: different bars have different heights, advancing with wavePhase
                val pattern = abs(sin(i * 3.0 * PI / numBars + snapshot.wavePhase)).toFloat()
                val barLen  = barBase + snapshot.amplitude * barMax * pattern

                val startX = cx + edgeR * cos(angle)
                val startY = cy + edgeR * sin(angle)
                val endX   = cx + (edgeR + barLen) * cos(angle)
                val endY   = cy + (edgeR + barLen) * sin(angle)

                drawLine(
                    color       = snapshot.primaryColor.copy(alpha = 0.45f + 0.55f * pattern),
                    start       = Offset(startX, startY),
                    end         = Offset(endX, endY),
                    strokeWidth = barWidth,
                    cap         = StrokeCap.Round
                )
            }
        }

        // ── 4. Processing arcs (rotation) ─────────────────────────────────────
        if (snapshot.rotationEnabled) {
            val arcRadius = scaledOrbRadius * 1.28f
            val arcTopLeft = Offset(cx - arcRadius, cy - arcRadius)
            val arcSize    = Size(arcRadius * 2f, arcRadius * 2f)
            val arcStroke  = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)

            // Primary arc — 90° sweep
            drawArc(
                color      = snapshot.primaryColor.copy(alpha = 0.90f),
                startAngle = snapshot.rotationDegrees,
                sweepAngle = 90f,
                useCenter  = false,
                topLeft    = arcTopLeft,
                size       = arcSize,
                style      = arcStroke
            )
            // Secondary arc — 60° sweep, 180° offset, dimmer
            drawArc(
                color      = snapshot.primaryColor.copy(alpha = 0.45f),
                startAngle = snapshot.rotationDegrees + 180f,
                sweepAngle = 60f,
                useCenter  = false,
                topLeft    = arcTopLeft,
                size       = arcSize,
                style      = arcStroke
            )
        }

        // ── 5. Orb core (radial gradient fill) ────────────────────────────────
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    snapshot.primaryColor.copy(alpha = snapshot.coreAlpha),
                    snapshot.primaryColor.copy(alpha = snapshot.coreAlpha * 0.55f),
                    snapshot.primaryColor.copy(alpha = snapshot.coreAlpha * 0.15f),
                    Color.Transparent
                ),
                center = center,
                radius = scaledOrbRadius
            ),
            radius = scaledOrbRadius,
            center = center
        )

        // ── 6. Specular highlight (upper-left micro-glow) ─────────────────────
        val hlRadius = scaledOrbRadius * 0.38f
        val hlCenter = Offset(
            cx - scaledOrbRadius * 0.20f,
            cy - scaledOrbRadius * 0.22f
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    snapshot.primaryColor.copy(alpha = snapshot.coreAlpha * 0.45f),
                    Color.Transparent
                ),
                center = hlCenter,
                radius = hlRadius
            ),
            radius = hlRadius,
            center = hlCenter
        )
    }
}
