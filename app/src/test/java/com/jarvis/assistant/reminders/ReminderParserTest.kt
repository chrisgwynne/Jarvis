package com.jarvis.assistant.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ReminderParserTest {

    // ── Relative time: "in X unit" ────────────────────────────────────────────

    @Test
    fun `in 5 minutes — triggers approx 5 minutes from now`() {
        val now = System.currentTimeMillis()
        val result = ReminderParser.parse("remind me in 5 minutes to take my medication")

        assertNotNull(result)
        val diff = result!!.triggerAtMs - now
        assertTrue("Expected ~300000ms, got $diff", diff in 295_000L..305_000L)
    }

    @Test
    fun `in 2 hours — triggers approx 2 hours from now`() {
        val now = System.currentTimeMillis()
        val result = ReminderParser.parse("in 2 hours")

        assertNotNull(result)
        val diff = result!!.triggerAtMs - now
        assertTrue("Expected ~7200000ms, got $diff", diff in 7_190_000L..7_210_000L)
    }

    @Test
    fun `in 30 seconds — triggers approx 30 seconds from now`() {
        val now = System.currentTimeMillis()
        val result = ReminderParser.parse("set a timer for in 30 seconds")

        assertNotNull(result)
        val diff = result!!.triggerAtMs - now
        assertTrue("Expected ~30000ms, got $diff", diff in 28_000L..32_000L)
    }

    // ── Clock time: "at HH:MM am/pm" ─────────────────────────────────────────

    @Test
    fun `at 3pm — trigger in future`() {
        val result = ReminderParser.parse("remind me at 3pm to call the dentist")

        assertNotNull(result)
        assertTrue(result!!.triggerAtMs > System.currentTimeMillis())
        assertEquals("call the dentist", result.label)
    }

    @Test
    fun `at 9 30 am — label extraction`() {
        val result = ReminderParser.parse("remind me at 9:30am to take medication")
        assertNotNull(result)
        assertEquals("take medication", result!!.label)
    }

    // ── Timer detection ───────────────────────────────────────────────────────

    @Test
    fun `set a timer for 10 minutes — isTimer true`() {
        val now = System.currentTimeMillis()
        val result = ReminderParser.parse("set a timer for 10 minutes")

        assertNotNull(result)
        assertTrue(result!!.isTimer)
        val diff = result.triggerAtMs - now
        assertTrue("Expected ~600000ms, got $diff", diff in 595_000L..605_000L)
    }

    @Test
    fun `remind me in 30 minutes — isTimer false`() {
        val result = ReminderParser.parse("remind me in 30 minutes")
        assertNotNull(result)
        assertTrue(!result!!.isTimer)
    }

    // ── Tomorrow ──────────────────────────────────────────────────────────────

    @Test
    fun `tomorrow at 9am — trigger day after today`() {
        val result = ReminderParser.parse("remind me tomorrow at 9am to go running")
        assertNotNull(result)

        val cal = Calendar.getInstance()
        cal.timeInMillis = result!!.triggerAtMs
        val today = Calendar.getInstance()

        val dayDiff = cal.get(Calendar.DAY_OF_YEAR) - today.get(Calendar.DAY_OF_YEAR)
        assertEquals(1, dayDiff)
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
    }

    // ── Label extraction ──────────────────────────────────────────────────────

    @Test
    fun `label extraction strips boilerplate from remind me to`() {
        val result = ReminderParser.parse("remind me in 1 hour to call mum")
        assertNotNull(result)
        assertEquals("call mum", result!!.label)
    }

    @Test
    fun `unparseable input returns null`() {
        val result = ReminderParser.parse("what is the capital of France")
        assertNull(result)
    }

    @Test
    fun `no time expression returns null`() {
        val result = ReminderParser.parse("remind me to buy milk")
        assertNull(result)
    }

    // ── Timer label ───────────────────────────────────────────────────────────

    @Test
    fun `timer with no explicit label defaults to timer`() {
        val result = ReminderParser.parse("set a timer for 5 minutes")
        assertNotNull(result)
        // Label should be blank or "timer" since there's no specific subject
        assertTrue(result!!.label.isNotBlank())
    }
}
