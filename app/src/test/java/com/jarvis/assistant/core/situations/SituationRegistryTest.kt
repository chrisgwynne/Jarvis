package com.jarvis.assistant.core.situations

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SituationRegistryTest {

    private val nowMs = 1_714_000_000_000L

    private fun sit(
        type: SituationType,
        conf: Float = 0.7f,
        urg: Float = 0.7f,
        expiresAtMs: Long = nowMs + 60_000L,
    ) = Situation(
        type = type,
        confidence = conf, urgency = urg,
        createdAtMs = nowMs, expiresAtMs = expiresAtMs,
        evidence = emptyList(), sourceSignals = emptyList(), summary = "",
    )

    @Test
    fun `update replaces active set sorted by weight`() = runBlocking {
        val reg = SituationRegistry()
        val low = sit(SituationType.TIRED_LATE_NIGHT_INTERACTION, conf = 0.9f, urg = 0.1f)
        val hi = sit(SituationType.LEAVING_HOME_SOON, conf = 0.5f, urg = 0.95f)
        reg.update(listOf(low, hi), nowMs)
        val snap = reg.snapshot.value
        assertEquals(2, snap.size)
        assertEquals(SituationType.LEAVING_HOME_SOON, snap.first().type)
        assertTrue(reg.has(SituationType.TIRED_LATE_NIGHT_INTERACTION))
    }

    @Test
    fun `duplicate types collapse to highest weight`() = runBlocking {
        val reg = SituationRegistry()
        val a = sit(SituationType.LEAVING_HOME_SOON, conf = 0.5f, urg = 0.5f)
        val b = sit(SituationType.LEAVING_HOME_SOON, conf = 0.9f, urg = 0.9f)
        reg.update(listOf(a, b), nowMs)
        assertEquals(1, reg.snapshot.value.size)
        assertEquals(0.9f, reg.snapshot.value.first().confidence)
    }

    @Test
    fun `expired entries are filtered on update`() = runBlocking {
        val reg = SituationRegistry()
        val stale = sit(SituationType.POSSIBLE_DELIVERY_WAITING, expiresAtMs = nowMs - 1)
        reg.update(listOf(stale), nowMs)
        assertTrue(reg.snapshot.value.isEmpty())
    }

    @Test
    fun `sweep evicts expired without replacing set`() = runBlocking {
        val reg = SituationRegistry()
        val fresh = sit(SituationType.IN_MEETING_AND_UNAVAILABLE, expiresAtMs = nowMs + 10_000L)
        val dying = sit(SituationType.POSSIBLE_DELIVERY_WAITING, expiresAtMs = nowMs + 10_000L)
        reg.update(listOf(fresh, dying), nowMs)
        assertEquals(2, reg.snapshot.value.size)
        reg.sweep(nowMs + 20_000L)
        assertTrue(reg.snapshot.value.isEmpty())
    }

    @Test
    fun `top returns null when empty`() {
        val reg = SituationRegistry()
        assertNull(reg.top())
        assertFalse(reg.has(SituationType.LOW_BATTERY_BEFORE_TRAVEL))
    }
}
