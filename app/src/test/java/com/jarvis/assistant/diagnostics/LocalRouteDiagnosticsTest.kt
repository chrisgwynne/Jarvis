package com.jarvis.assistant.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRouteDiagnosticsTest {

    @After
    fun tearDown() { LocalRouteDiagnostics.clear() }

    @Test
    fun `record appends newest-first`() {
        LocalRouteDiagnostics.clear()
        LocalRouteDiagnostics.record(
            transcript           = "a",
            normalisedTranscript = "a",
            intent               = "TIME",
            tool                 = "time",
            result               = "success",
            latencyMs            = 50,
        )
        LocalRouteDiagnostics.record(
            transcript           = "b",
            normalisedTranscript = "b",
            intent               = "VOLUME",
            tool                 = "volume_control",
            result               = "success",
            latencyMs            = 80,
        )
        val s = LocalRouteDiagnostics.snapshot()
        assertEquals(2, s.size)
        assertEquals("b", s.first().transcript)
        assertEquals("a", s.last().transcript)
    }

    @Test
    fun `clear empties the buffer`() {
        LocalRouteDiagnostics.record(
            transcript = "x", normalisedTranscript = "x",
            intent = "TIME", tool = "time", result = "success", latencyMs = 10,
        )
        LocalRouteDiagnostics.clear()
        assertTrue(LocalRouteDiagnostics.snapshot().isEmpty())
    }

    @Test
    fun `remoteTouched flag is preserved`() {
        LocalRouteDiagnostics.clear()
        LocalRouteDiagnostics.record(
            transcript = "x", normalisedTranscript = "x",
            intent = "TIME", tool = "time", result = "success",
            latencyMs = 10, remoteTouched = false,
        )
        val e = LocalRouteDiagnostics.snapshot().first()
        assertFalse(e.remoteTouched)
    }

    @Test
    fun `capacity bound is enforced`() {
        LocalRouteDiagnostics.clear()
        for (i in 0..40) {
            LocalRouteDiagnostics.record(
                transcript = "t$i", normalisedTranscript = "t$i",
                intent = "I", tool = "tool", result = "success", latencyMs = 1,
            )
        }
        // Capacity is 30 per the implementation; assert it's bounded.
        assertTrue(LocalRouteDiagnostics.snapshot().size <= 30)
    }

    @Test
    fun `slot truncation prevents unbounded growth`() {
        LocalRouteDiagnostics.clear()
        val huge = "x".repeat(500)
        LocalRouteDiagnostics.record(
            transcript = "x", normalisedTranscript = "x",
            intent = "I", tool = "t",
            slots = mapOf("k" to huge),
            result = "success", latencyMs = 1,
        )
        val e = LocalRouteDiagnostics.snapshot().first()
        assertTrue("slot value should be truncated",
            e.slots["k"]!!.length <= 80)
    }
}
