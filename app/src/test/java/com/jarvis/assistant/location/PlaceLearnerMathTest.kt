package com.jarvis.assistant.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure-math helpers on PlaceLearner that don't require Context.
 * The persistence + observe/classify paths are covered by instrumented tests
 * (or would need Robolectric for SharedPreferences).
 */
class PlaceLearnerMathTest {

    @Test
    fun round4TruncatesToFourDecimals() {
        assertEquals(51.5074, PlaceLearner.round4(51.5074321), 1e-6)
        assertEquals(-0.1278, PlaceLearner.round4(-0.12781234), 1e-6)
    }

    @Test
    fun distanceMetersMatchesEquirectangular() {
        // Roughly 1 km at latitude 51 (~110 m / 0.001 deg lat).
        val d = PlaceLearner.distanceMeters(51.5, -0.1, 51.509, -0.1)
        assertTrue("Expected ~1 km, got $d m", d in 950.0..1100.0)
    }

    @Test
    fun distanceMetersSymmetric() {
        val a = PlaceLearner.distanceMeters(51.5, -0.1, 51.6, -0.2)
        val b = PlaceLearner.distanceMeters(51.6, -0.2, 51.5, -0.1)
        assertEquals(a, b, 1.0)
    }
}
