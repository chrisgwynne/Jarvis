package com.jarvis.assistant.audio.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeThresholdAdaptorTest {

    @Test fun `quiet ambient keeps threshold at base`() {
        val a = WakeThresholdAdaptor(base = 0.5f, max = 0.85f)
        assertEquals(0.5f, a.adapt(0.02f), 1e-6f)
        assertEquals(0.5f, a.adapt(0.05f), 1e-6f)
    }

    @Test fun `loud ambient lifts threshold to max`() {
        val a = WakeThresholdAdaptor(base = 0.5f, max = 0.85f)
        assertEquals(0.85f, a.adapt(0.9f), 1e-6f)
    }

    @Test fun `mid noise is interpolated`() {
        val a = WakeThresholdAdaptor(
            base = 0.5f, max = 0.85f, quietThreshold = 0.1f, loudThreshold = 0.5f
        )
        // halfway between quiet (0.1) and loud (0.5) → 0.3 → midway threshold
        val mid = a.adapt(0.3f)
        assertTrue("expected ~0.675, got $mid", kotlin.math.abs(mid - 0.675f) < 0.02f)
    }

    @Test fun `output never exceeds safe max`() {
        val a = WakeThresholdAdaptor(base = 0.5f, max = 0.85f)
        for (n in 0..100) a.adapt(n / 100f)
        assertTrue(a.currentThreshold() <= 0.85f)
    }

    @Test fun `hysteresis suppresses jitter under 0_02`() {
        val a = WakeThresholdAdaptor(
            base = 0.5f, max = 0.85f, hysteresis = 0.02f
        )
        a.adapt(0.5f)           // jump to 0.85
        val first = a.currentThreshold()
        // Tiny wiggle should not move the threshold.
        a.adapt(0.49f)
        assertEquals(first, a.currentThreshold(), 1e-6f)
    }

    @Test fun `reset restores base`() {
        val a = WakeThresholdAdaptor(base = 0.5f, max = 0.85f)
        a.adapt(0.9f)
        assertEquals(0.85f, a.currentThreshold(), 1e-6f)
        a.reset()
        assertEquals(0.5f, a.currentThreshold(), 1e-6f)
    }

    @Test fun `noise out of range is clamped`() {
        val a = WakeThresholdAdaptor(base = 0.5f, max = 0.85f)
        assertEquals(0.5f, a.adapt(-1f), 1e-6f)
        assertEquals(0.85f, a.adapt(5f), 1e-6f)
    }
}
