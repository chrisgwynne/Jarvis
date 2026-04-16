package com.jarvis.assistant.followup

import org.junit.Assert.*
import org.junit.Test

class CorrectionHandlerTest {

    private fun messageFlow(
        contact: String? = null,
        body: String? = null
    ): ActiveFlow {
        val flow = ActiveFlow(
            type = FlowType.MESSAGE_DRAFT,
            expiresAt = System.currentTimeMillis() + 300_000L,
            missingSlots = ArrayDeque(listOf(SlotKey.TARGET_CONTACT, SlotKey.MESSAGE_BODY))
        )
        contact?.let { flow.fillSlot(SlotKey.TARGET_CONTACT, it) }
        body?.let { flow.fillSlot(SlotKey.MESSAGE_BODY, it) }
        return flow
    }

    private fun reminderFlow(content: String? = null): ActiveFlow {
        val flow = ActiveFlow(
            type = FlowType.REMINDER_CREATION,
            expiresAt = System.currentTimeMillis() + 300_000L,
            missingSlots = ArrayDeque(listOf(SlotKey.REMINDER_CONTENT, SlotKey.TRIGGER_TIME))
        )
        content?.let { flow.fillSlot(SlotKey.REMINDER_CONTENT, it) }
        return flow
    }

    // ── "not X, Y" pattern ────────────────────────────────────────────────────

    @Test
    fun `not Chris James — corrects contact`() {
        val flow = messageFlow(contact = "Chris")
        val result = CorrectionHandler.detect("not Chris, James", flow)
        assertNotNull(result)
        assertEquals(SlotKey.TARGET_CONTACT, result!!.slot)
        assertEquals("James", result.newValue)
    }

    @Test
    fun `not Chris send to Sarah — corrects contact`() {
        val flow = messageFlow(contact = "Chris")
        val result = CorrectionHandler.detect("not Chris, send it to Sarah", flow)
        assertNotNull(result)
        assertEquals(SlotKey.TARGET_CONTACT, result!!.slot)
    }

    // ── "actually" pattern ────────────────────────────────────────────────────

    @Test
    fun `actually James — corrects contact`() {
        val flow = messageFlow(contact = "Chris")
        val result = CorrectionHandler.detect("actually James", flow)
        assertNotNull(result)
        assertEquals(SlotKey.TARGET_CONTACT, result!!.slot)
        assertEquals("James", result.newValue)
    }

    @Test
    fun `actually make it 30 minutes — corrects trigger time`() {
        val flow = reminderFlow(content = "take medication")
        // Give the flow a trigger time first so correction detects it
        flow.fillSlot(SlotKey.TRIGGER_TIME, (System.currentTimeMillis() + 600_000L).toString())
        val result = CorrectionHandler.detect("actually make it 30 minutes", flow)
        assertNotNull(result)
        assertEquals(SlotKey.TRIGGER_TIME, result!!.slot)
    }

    // ── "no," pattern ─────────────────────────────────────────────────────────

    @Test
    fun `no use mobile — corrects phone type`() {
        val flow = ActiveFlow(
            type = FlowType.CALL_CONTACT,
            expiresAt = System.currentTimeMillis() + 300_000L,
            missingSlots = ArrayDeque(listOf(SlotKey.PHONE_TYPE))
        )
        flow.fillSlot(SlotKey.TARGET_CONTACT, "Sarah")
        val result = CorrectionHandler.detect("no, mobile", flow)
        assertNotNull(result)
        assertEquals(SlotKey.PHONE_TYPE, result!!.slot)
    }

    // ── "change it to" pattern ─────────────────────────────────────────────────

    @Test
    fun `change it to tomorrow — corrects trigger time hint`() {
        val flow = reminderFlow(content = "call doctor")
        val result = CorrectionHandler.detect("change it to tomorrow", flow)
        assertNotNull(result)
        assertEquals(SlotKey.TRIGGER_TIME, result!!.slot)
    }

    // ── No correction ─────────────────────────────────────────────────────────

    @Test
    fun `regular sentence does not trigger correction`() {
        val flow = messageFlow(contact = "Chris")
        val result = CorrectionHandler.detect("I'm running late", flow)
        assertNull(result)
    }

    @Test
    fun `plain phone type word does not trigger correction`() {
        // "work" alone should NOT be a correction — it's a slot fill
        val flow = messageFlow(contact = "Chris")
        val result = CorrectionHandler.detect("work", flow)
        assertNull(result)
    }
}
