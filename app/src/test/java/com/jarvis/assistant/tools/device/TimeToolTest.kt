package com.jarvis.assistant.tools.device

import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

class TimeToolTest {

    private val tool = TimeTool()

    @Test
    fun `'what time is it' matches as TIME`() {
        val input = tool.matches("what time is it")
        assertNotNull(input)
        assertEquals("time", input!!.param("kind"))
    }

    @Test
    fun `'whats the time' matches`() {
        val input = tool.matches("what's the time")
        assertNotNull(input)
        assertEquals("time", input!!.param("kind"))
    }

    @Test
    fun `'time please' matches`() {
        assertNotNull(tool.matches("time please"))
    }

    @Test
    fun `'tell me the time' matches`() {
        assertNotNull(tool.matches("tell me the time"))
    }

    @Test
    fun `'whats the date' matches as DATE`() {
        val input = tool.matches("what's the date")
        assertNotNull(input)
        assertEquals("date", input!!.param("kind"))
    }

    @Test
    fun `'what day is it' matches DATE`() {
        assertEquals("date", tool.matches("what day is it")!!.param("kind"))
        assertEquals("date", tool.matches("what day is today")!!.param("kind"))
    }

    @Test
    fun `unrelated utterances do not match`() {
        assertNull(tool.matches("set a timer for 5 minutes"))
        assertNull(tool.matches("set an alarm for 7 am"))
        assertNull(tool.matches("send Mike a WhatsApp"))
        assertNull(tool.matches("open Spotify"))
        // Compound that mentions "time" but isn't asking for the time —
        // anchored regex declines it cleanly.
        assertNull(tool.matches("set an alarm for what time mum gets home"))
    }

    @Test
    fun `executes synchronously for TIME and DATE`() = runBlocking {
        val time = tool.execute(ToolInput("what time is it", mapOf("kind" to "time")))
        assertTrue(time is ToolResult.Success)
        assertTrue((time as ToolResult.Success).spokenFeedback.startsWith("It's "))

        val date = tool.execute(ToolInput("what's the date", mapOf("kind" to "date")))
        assertTrue(date is ToolResult.Success)
        val spoken = (date as ToolResult.Success).spokenFeedback
        // Always starts with a weekday name
        assertTrue("date reply should start with a weekday — got '$spoken'",
            spoken.split(",").first().lowercase(Locale.UK) in
                setOf("monday","tuesday","wednesday","thursday","friday","saturday","sunday"))
    }

    @Test
    fun `formatDate uses ordinal suffixes correctly`() {
        // Pin to a known date so the assertion is stable.
        val cal = GregorianCalendar(TimeZone.getDefault())
        cal.set(2026, Calendar.MAY, 1)
        val out1 = tool.formatDate(cal.time)
        assertTrue("'$out1'", out1.contains("1st of May"))

        cal.set(2026, Calendar.MAY, 2)
        assertTrue(tool.formatDate(cal.time).contains("2nd of May"))

        cal.set(2026, Calendar.MAY, 3)
        assertTrue(tool.formatDate(cal.time).contains("3rd of May"))

        cal.set(2026, Calendar.MAY, 11)
        assertTrue(tool.formatDate(cal.time).contains("11th of May"))

        cal.set(2026, Calendar.MAY, 21)
        assertTrue(tool.formatDate(cal.time).contains("21st of May"))
    }
}
