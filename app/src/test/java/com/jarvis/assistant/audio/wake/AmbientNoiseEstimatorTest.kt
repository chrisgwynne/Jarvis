package com.jarvis.assistant.audio.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientNoiseEstimatorTest {

    /** A 1.5k-sample frame at the given peak amplitude (square wave-ish). */
    private fun frame(amp: Short, n: Int = 1536): ShortArray =
        ShortArray(n) { if (it and 1 == 0) amp else (-amp).toShort() }

    @Test fun `silent frames yield ~0 noise level`() {
        val est = AmbientNoiseEstimator()
        repeat(60) { est.observe(ShortArray(1536)) }
        assertTrue("noise=${est.noiseLevel()}", est.noiseLevel() < 0.02f)
    }

    @Test fun `loud frames push noise level up`() {
        val est = AmbientNoiseEstimator()
        repeat(120) { est.observe(frame(20_000)) }
        assertTrue("noise=${est.noiseLevel()}", est.noiseLevel() > 0.5f)
    }

    @Test fun `pause stops accumulation`() {
        val est = AmbientNoiseEstimator()
        // Drive the estimator into a mid-range level first.
        repeat(20) { est.observe(frame(8_000)) }
        val before = est.noiseLevel()
        assertTrue("Pre-condition: estimator should have ramped past 0", before > 0f)

        est.pause()
        repeat(60) { est.observe(frame(30_000)) }   // ignored
        // After pause, the level must not have moved.
        assertEquals(before, est.noiseLevel(), 1e-4f)

        est.resume()
        // Resumed observations of louder frames must move the level upward
        // OR keep it at the clamped maximum if the louder signal saturates.
        repeat(60) { est.observe(frame(30_000)) }
        val after = est.noiseLevel()
        assertTrue("after=$after vs before=$before", after >= before)
    }

    @Test fun `reset clears state`() {
        val est = AmbientNoiseEstimator()
        repeat(60) { est.observe(frame(15_000)) }
        assertTrue(est.noiseLevel() > 0f)
        est.reset()
        assertEquals(0f, est.noiseLevel(), 1e-6f)
    }

    @Test fun `output is clamped to 0_1`() {
        // very small full-scale RMS → any audible signal saturates to 1
        val est = AmbientNoiseEstimator(fullScaleRms = 1f)
        est.observe(frame(20_000))
        assertEquals(1f, est.noiseLevel(), 1e-6f)
    }
}
