package com.jarvis.assistant.todoist.edit

import com.jarvis.assistant.todoist.TodoistPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

class ConversationalEditParserTest {

    private val now: Long = GregorianCalendar(TimeZone.getTimeZone("UTC"))
        .apply { set(2026, Calendar.MAY, 13, 10, 0, 0); set(Calendar.MILLISECOND, 0) }
        .timeInMillis

    @Test
    fun `move that to tomorrow yields Reschedule with tomorrow date`() {
        val e = ConversationalEditParser.parse("move that to tomorrow", now)
        assertTrue(e is ConversationalEditParser.Edit.Reschedule)
        val r = e as ConversationalEditParser.Edit.Reschedule
        assertEquals("2026-05-14", r.date)
    }

    @Test
    fun `actually 9pm yields Reschedule with time only`() {
        val e = ConversationalEditParser.parse("actually 9pm", now)
        assertTrue(e is ConversationalEditParser.Edit.Reschedule)
        assertEquals("21:00", (e as ConversationalEditParser.Edit.Reschedule).time)
    }

    @Test
    fun `make that urgent yields SetPriority URGENT`() {
        val e = ConversationalEditParser.parse("make that urgent", now)
        assertTrue(e is ConversationalEditParser.Edit.SetPriority)
        assertEquals(TodoistPriority.URGENT,
            (e as ConversationalEditParser.Edit.SetPriority).priority)
    }

    @Test
    fun `make it high priority maps to HIGH`() {
        val e = ConversationalEditParser.parse("make it high", now)
        assertTrue(e is ConversationalEditParser.Edit.SetPriority)
        assertEquals(TodoistPriority.HIGH,
            (e as ConversationalEditParser.Edit.SetPriority).priority)
    }

    @Test
    fun `put that in work yields MoveProject=work`() {
        val e = ConversationalEditParser.parse("put that in work", now)
        assertTrue(e is ConversationalEditParser.Edit.MoveProject)
        assertEquals("work", (e as ConversationalEditParser.Edit.MoveProject).projectHint)
    }

    @Test
    fun `delete that yields Delete`() {
        val e = ConversationalEditParser.parse("delete that", now)
        assertTrue(e is ConversationalEditParser.Edit.Delete)
    }

    @Test
    fun `mark that done yields Complete`() {
        val e = ConversationalEditParser.parse("mark that done", now)
        assertTrue(e is ConversationalEditParser.Edit.Complete)
    }

    @Test
    fun `snooze that 15 minutes`() {
        val e = ConversationalEditParser.parse("snooze that 15 minutes", now)
        assertTrue(e is ConversationalEditParser.Edit.Snooze)
        assertEquals(15, (e as ConversationalEditParser.Edit.Snooze).minutes)
    }

    @Test
    fun `remind me again in 10 minutes is a Snooze`() {
        val e = ConversationalEditParser.parse("remind me again in 10 minutes", now)
        assertTrue(e is ConversationalEditParser.Edit.Snooze)
        assertEquals(10, (e as ConversationalEditParser.Edit.Snooze).minutes)
    }

    @Test
    fun `utterance without anchor returns null`() {
        // No "that"/"it"/"actually" anchor — refuses to claim the utterance
        // even though it would otherwise parse as a priority command.
        assertNull(ConversationalEditParser.parse("urgent", now))
        assertNull(ConversationalEditParser.parse("make stuff happen", now))
    }

    @Test
    fun `looksLikeEdit predicate matches every supported form`() {
        listOf(
            "move that to tomorrow",
            "actually 9pm",
            "make that urgent",
            "put that in work",
            "delete that",
            "mark that done",
            "snooze that 15 minutes",
            "remind me again in 10 minutes",
        ).forEach {
            assertTrue("'$it' should be classified as edit",
                ConversationalEditParser.looksLikeEdit(it))
        }
        // Negatives
        listOf(
            "what time is it",
            "remind me to call mum",
            "send mike a whatsapp",
        ).forEach {
            assertTrue("'$it' should NOT be classified as edit (no anchor)",
                !ConversationalEditParser.looksLikeEdit(it))
        }
    }
}
