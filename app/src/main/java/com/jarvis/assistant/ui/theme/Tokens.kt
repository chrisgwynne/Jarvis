package com.jarvis.assistant.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * JarvisTokens — single source of truth for spacing, radii, elevations and
 * motion timing.  Use these instead of literal dp / Int values so visual
 * tweaks land in one place rather than being chased through every screen.
 *
 * Naming follows Material 3 spec where it makes sense (4dp grid for spacing,
 * named radii rather than "small/medium/large" so the relationship between
 * sizes is obvious from the call site).
 */
object JarvisTokens {

    /** 4dp grid spacing scale.  Stick to these in layout padding/spacing. */
    object Space {
        val xxs = 2.dp
        val xs  = 4.dp
        val sm  = 8.dp
        val md  = 12.dp
        val lg  = 16.dp
        val xl  = 24.dp
        val xxl = 32.dp
        val xxxl = 48.dp
    }

    /** Corner radius scale.  Small=chips, Medium=rows, Large=cards/groups. */
    object Radius {
        val xs   = 4.dp
        val sm   = 6.dp
        val md   = 10.dp
        val lg   = 14.dp
        val xl   = 20.dp
        val pill = 100.dp
    }

    /** Pre-built shapes for common reuse — avoids re-allocating per recompose. */
    object Shape {
        val chip  = RoundedCornerShape(Radius.sm)
        val row   = RoundedCornerShape(Radius.md)
        val card  = RoundedCornerShape(Radius.lg)
        val pill  = RoundedCornerShape(Radius.pill)
    }

    /** Surface elevation steps in dp.  Material 3 token-aligned. */
    object Elevation {
        val level0 = 0.dp
        val level1 = 1.dp
        val level2 = 3.dp
        val level3 = 6.dp
        val level4 = 8.dp
    }

    /** Animation duration tokens (ms). */
    object Motion {
        const val instant = 80
        const val fast    = 150
        const val medium  = 300
        const val slow    = 450
        /** Orb amplitude smoothing — slower than UI feedback to feel natural. */
        const val orb     = 120
    }

    /** Minimum tappable size for accessibility (Material 48dp minimum). */
    object Touch {
        val minTarget = 48.dp
    }
}
