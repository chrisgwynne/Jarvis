package com.jarvis.assistant.todoist.parse

import com.jarvis.assistant.todoist.TodoistPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

class ReminderIntentParserTest {

    // Pin clock so date assertions are stable.
    private val now: Long = GregorianCalendar(TimeZone.getTimeZone("UTC"))
        .apply { set(2026, Calendar.MAY, 13, 10, 0, 0); set(Calendar.MILLISECOND, 0) }
        .timeInMillis

    // ── Required regression cases ────────────────────────────────────────

    @Test
    fun `remind me to take bins out tomorrow at 7`() {
        val m = ReminderIntentParser.parse("remind me to take bins out tomorrow at 7", now)
        assertNotNull(m)
        assertEquals(ReminderIntentParser.Kind.REMINDER, m!!.kind)
        assertTrue("content should contain 'bins': ${m.content}",
            m.content.contains("bins"))
        assertEquals("2026-05-14", m.date)
        // "7" w/ no am/pm but no evening word — bare hour defaults to NOT pm
        // here; the loose-time path is used by follow-up only.  Either time
        // form is acceptable as long as it's a valid HH:mm.
        assertNotNull(m.time)
    }

    @Test
    fun `remind me about MOT on July 12th at 9am`() {
        val m = ReminderIntentParser.parse("remind me about MOT on July 12th at 9am", now)
        assertNotNull(m)
        assertEquals("2026-07-12", m!!.date)
        assertEquals("09:00", m.time)
        assertTrue(m.content.lowercase().contains("mot"))
    }

    @Test
    fun `add buy milk to my reminders`() {
        val m = ReminderIntentParser.parse("add buy milk to my reminders", now)
        assertNotNull(m)
        assertEquals(ReminderIntentParser.Kind.REMINDER, m!!.kind)
        assertTrue("got '${m.content}'", m.content.contains("buy milk"))
    }

    @Test
    fun `todo call Mike`() {
        val m = ReminderIntentParser.parse("todo call Mike", now)
        assertNotNull(m)
        assertEquals(ReminderIntentParser.Kind.TASK, m!!.kind)
        assertTrue(m.content.contains("call mike", ignoreCase = true))
    }

    @Test
    fun `add a task to phone the dentist`() {
        val m = ReminderIntentParser.parse("add a task to phone the dentist", now)
        assertNotNull(m)
        assertEquals(ReminderIntentParser.Kind.TASK, m!!.kind)
        assertTrue(m.content.contains("dentist"))
    }

    @Test
    fun `dont let me forget to order filament`() {
        val m = ReminderIntentParser.parse("don't let me forget to order filament", now)
        assertNotNull(m)
        assertEquals(ReminderIntentParser.Kind.REMINDER, m!!.kind)
        assertTrue(m.content.contains("filament"))
    }

    @Test
    fun `remind me when I get home to feed the dog`() {
        val m = ReminderIntentParser.parse("remind me when I get home to feed the dog", now)
        assertNotNull(m)
        assertNotNull(m!!.contextTrigger)
        assertEquals(
            ReminderIntentParser.ContextTriggerType.ARRIVE_HOME,
            m.contextTrigger!!.type,
        )
        assertTrue(m.content.contains("feed the dog"))
    }

    @Test
    fun `remind me next time I open Etsy`() {
        val m = ReminderIntentParser.parse("remind me next time I open Etsy to reply to messages", now)
        assertNotNull(m)
        assertEquals(
            ReminderIntentParser.ContextTriggerType.APP_OPEN,
            m!!.contextTrigger!!.type,
        )
        assertTrue("payload should be 'etsy': '${m.contextTrigger.payload}'",
            m.contextTrigger.payload?.contains("etsy", ignoreCase = true) == true)
    }

    @Test
    fun `keep reminding me every 10 minutes`() {
        val m = ReminderIntentParser.parse("remind me to take the pill, keep reminding me every 10 minutes", now)
        assertNotNull(m)
        assertNotNull(m!!.repeat)
        assertTrue(m.repeat!!.intervalNaturalString.contains("10 minutes"))
    }

    @Test
    fun `non-reminder utterance returns null`() {
        assertNull(ReminderIntentParser.parse("what time is it", now))
        assertNull(ReminderIntentParser.parse("send Mike a WhatsApp", now))
        assertNull(ReminderIntentParser.parse("call mum", now))
    }

    @Test
    fun `priority p1 detected`() {
        val m = ReminderIntentParser.parse("todo call Mike p1 tomorrow", now)
        assertNotNull(m)
        assertEquals(TodoistPriority.URGENT, m!!.priority)
    }

    @Test
    fun `recurrence in fresh utterance`() {
        val m = ReminderIntentParser.parse("remind me every day at 8am to journal", now)
        assertNotNull(m)
        assertNotNull(m!!.recurrence)
        // No follow-up needed when recurrence is set.
        assertFalse(m.needsTimeFollowUp)
    }

    @Test
    fun `needsTimeFollowUp true when reminder has no time`() {
        val m = ReminderIntentParser.parse("remind me to call dentist", now)
        assertNotNull(m)
        assertTrue(m!!.needsTimeFollowUp)
    }

    // ── Audit regression cases ────────────────────────────────────────────

    @Test
    fun `smart PM - tomorrow at 7 resolves to 19_00`() {
        val m = ReminderIntentParser.parse("remind me to take bins out tomorrow at 7", now)
        assertEquals("19:00", m!!.time)
    }

    @Test
    fun `put X on my work list captures project=work`() {
        val m = ReminderIntentParser.parse("put printer maintenance on my work list", now)
        assertNotNull(m)
        assertEquals("work", m!!.projectHint)
        assertEquals("printer maintenance", m.content)
    }

    @Test
    fun `bare friday resolves to next friday and strips from content`() {
        // 2026-05-13 is Wednesday → next Friday is 2026-05-15.
        val m = ReminderIntentParser.parse("I need to remember to pay the invoice Friday", now)
        assertNotNull(m)
        assertEquals("2026-05-15", m!!.date)
        // "friday" should be stripped from the content.
        assertFalse("content should not contain 'friday': '${m.content}'",
            m.content.contains("friday", ignoreCase = true))
    }

    @Test
    fun `tonight resolves date and default evening time`() {
        val m = ReminderIntentParser.parse("remind me tonight to lock the door", now)
        assertNotNull(m)
        assertEquals("2026-05-13", m!!.date)
        assertEquals("20:00", m.time)
        assertFalse("should NOT need a time follow-up — tonight implies evening",
            m.needsTimeFollowUp)
    }

    @Test
    fun `add buy milk to my reminders still parses after project regex change`() {
        // Regression for the "put X on my work list" fix — make sure the
        // simpler "add X to my reminders" form still goes through.
        val m = ReminderIntentParser.parse("add buy milk to my reminders", now)
        assertNotNull(m)
        assertEquals("buy milk", m!!.content)
    }

    @Test
    fun `Ive got to ring the dentist parses as reminder needing time`() {
        val m = ReminderIntentParser.parse("I've got to ring the dentist", now)
        assertNotNull(m)
        assertTrue(m!!.needsTimeFollowUp)
    }

    @Test
    fun `looksLikeReminderCommand classifies starters`() {
        listOf(
            "remind me to do thing",
            "set a reminder to call mum",
            "add task: take bins out",
            "todo call mike",
            "don't let me forget to buy milk",
            "put printer maintenance on my work list",
            "I need to remember to feed the dog",
        ).forEach {
            assertTrue("'$it' should be classified as reminder",
                ReminderIntentParser.looksLikeReminderCommand(it))
        }
        // Negatives
        listOf(
            "what time is it",
            "send mike a whatsapp",
            "open spotify",
            "where am i",
        ).forEach {
            assertFalse("'$it' should NOT be classified as reminder",
                ReminderIntentParser.looksLikeReminderCommand(it))
        }
    }
}
