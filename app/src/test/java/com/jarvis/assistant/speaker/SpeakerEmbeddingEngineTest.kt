package com.jarvis.assistant.speaker

import com.jarvis.assistant.speaker.audio.SpeakerEmbeddingEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerEmbeddingEngineTest {

    /** Returns a FloatArray of the given size filled with the same value, then L2-normalised. */
    private fun unitVector(size: Int, fillValue: Float): FloatArray {
        val v = FloatArray(size) { fillValue }
        val norm = Math.sqrt(v.map { it * it.toDouble() }.sum()).toFloat()
        return if (norm > 0f) FloatArray(size) { v[it] / norm } else v
    }

    // ── similarity ────────────────────────────────────────────────────────────

    @Test fun `identical vectors have similarity 1`() {
        val v = unitVector(39, 0.5f)
        val sim = SpeakerEmbeddingEngine.similarity(v, v)
        assertTrue("Expected ~1.0, got $sim", sim > 0.999f)
    }

    @Test fun `orthogonal vectors have similarity 0`() {
        val a = FloatArray(39) { if (it == 0) 1f else 0f }
        val b = FloatArray(39) { if (it == 1) 1f else 0f }
        val sim = SpeakerEmbeddingEngine.similarity(a, b)
        assertTrue("Expected ~0.0, got $sim", sim < 0.001f)
    }

    @Test fun `zero vector returns 0 similarity without exception`() {
        val v   = unitVector(39, 0.5f)
        val zero = FloatArray(39)
        val sim = SpeakerEmbeddingEngine.similarity(v, zero)
        assertEquals(0f, sim, 0.001f)
    }

    // ── bestMatch ─────────────────────────────────────────────────────────────

    @Test fun `bestMatch returns null for empty profiles`() {
        val probe = unitVector(39, 1f)
        val result = SpeakerEmbeddingEngine.bestMatch(probe, emptyMap())
        assertNull(result)
    }

    @Test fun `bestMatch returns the closest matching person`() {
        val probe    = unitVector(39, 1.0f)
        val matching = unitVector(39, 1.0f)    // identical to probe → highest similarity
        val other    = unitVector(39, -1.0f)   // opposite direction → lowest similarity

        val profiles = mapOf(
            1L to listOf(matching),
            2L to listOf(other)
        )
        val result = SpeakerEmbeddingEngine.bestMatch(probe, profiles)
        assertNotNull(result)
        assertEquals(1L, result!!.first)
        assertTrue(result.second > 0.99f)
    }

    @Test fun `bestMatch uses mean of multiple embeddings per person`() {
        val probe = unitVector(39, 1.0f)
        // Two embeddings for person 1: one matching, one opposite — mean should be near zero
        val personOneEmbeds = listOf(unitVector(39, 1.0f), unitVector(39, -1.0f))
        // One embedding for person 2: identical to probe
        val personTwoEmbeds = listOf(unitVector(39, 1.0f))

        val profiles = mapOf(
            1L to personOneEmbeds,
            2L to personTwoEmbeds
        )
        val result = SpeakerEmbeddingEngine.bestMatch(probe, profiles)
        assertNotNull(result)
        // Person 2 should win — their single embedding is an exact match
        assertEquals(2L, result!!.first)
    }

    // ── Threshold constants are sensible ─────────────────────────────────────

    @Test fun `high threshold is above low threshold`() {
        assertTrue(SpeakerEmbeddingEngine.THRESHOLD_HIGH > SpeakerEmbeddingEngine.THRESHOLD_LOW)
    }

    @Test fun `thresholds are within valid cosine range`() {
        assertTrue(SpeakerEmbeddingEngine.THRESHOLD_LOW  in 0f..1f)
        assertTrue(SpeakerEmbeddingEngine.THRESHOLD_HIGH in 0f..1f)
    }
}
