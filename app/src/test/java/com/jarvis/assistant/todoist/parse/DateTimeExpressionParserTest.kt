package com.jarvis.assistant.todoist.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

class DateTimeExpressionParserTest {

    // Pin the clock to Wednesday 2026-05-13 10:00 UTC so day-of-week
    // resolution is deterministic.
    private val now: Long = run {
        val cal = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.MAY, 13, 10, 0, 0); set(Calendar.MILLISECOND, 0)
        }
        cal.timeInMillis
    }
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `today resolves to current date`() {
        val r = DateTimeExpressionParser.parse("call mum today", now, utc)
        assertEquals("2026-05-13", r.date)
    }

    @Test
    fun `tomorrow rolls forward one day`() {
        val r = DateTimeExpressionParser.parse("take bins out tomorrow", now, utc)
        assertEquals("2026-05-14", r.date)
    }

    @Test
    fun `at 7pm produces 19_00`() {
        val r = DateTimeExpressionParser.parse("tomorrow at 7pm", now, utc)
        assertEquals("2026-05-14", r.date)
        assertEquals("19:00", r.time)
    }

    @Test
    fun `at 7 implicit evening when context says so`() {
        val r = DateTimeExpressionParser.parse("tomorrow evening at 7", now, utc)
        assertEquals("19:00", r.time)
    }

    @Test
    fun `at 9am explicit am`() {
        val r = DateTimeExpressionParser.parse("at 9am", now, utc)
        assertEquals("09:00", r.time)
    }

    @Test
    fun `next monday resolves to the upcoming monday`() {
        // 2026-05-13 is Wednesday → next Monday is 2026-05-18.
        val r = DateTimeExpressionParser.parse("next monday", now, utc)
        assertEquals("2026-05-18", r.date)
    }

    @Test
    fun `in 10 minutes resolves date and time`() {
        val r = DateTimeExpressionParser.parse("in 10 minutes", now, utc)
        assertEquals("2026-05-13", r.date)
        assertEquals("10:10", r.time)
    }

    @Test
    fun `in 2 hours resolves time`() {
        val r = DateTimeExpressionParser.parse("in 2 hours", now, utc)
        assertEquals("12:00", r.time)
    }

    @Test
    fun `explicit date july 12`() {
        val r = DateTimeExpressionParser.parse("on july 12 at 9am", now, utc)
        assertEquals("2026-07-12", r.date)
        assertEquals("09:00", r.time)
    }

    @Test
    fun `recurrence every monday`() {
        val r = DateTimeExpressionParser.parse("every monday at 9am", now, utc)
        assertTrue(r.isRecurring)
        assertNotNull(r.naturalString)
        assertTrue(r.naturalString!!.contains("monday"))
    }

    @Test
    fun `recurrence every day at 8am`() {
        val r = DateTimeExpressionParser.parse("every day at 8am", now, utc)
        assertTrue(r.isRecurring)
    }

    @Test
    fun `recurrence every weekday`() {
        val r = DateTimeExpressionParser.parse("every weekday", now, utc)
        assertTrue(r.isRecurring)
    }

    @Test
    fun `blank returns empty`() {
        val r = DateTimeExpressionParser.parse("", now, utc)
        assertTrue(r.isEmpty)
    }

    @Test
    fun `non-date utterance returns empty`() {
        val r = DateTimeExpressionParser.parse("call mike", now, utc)
        assertNull(r.date)
        assertNull(r.time)
        assertNull(r.naturalString)
    }
}
