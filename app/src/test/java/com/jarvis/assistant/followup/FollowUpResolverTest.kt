package com.jarvis.assistant.followup

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FollowUpResolverTest {

    private lateinit var tracker: EntityTracker

    @Before fun setUp() {
        tracker = EntityTracker()
    }

    private fun messageDraftFlow(contact: String? = "Chris"): ActiveFlow {
        val flow = ActiveFlow(
            type = FlowType.MESSAGE_DRAFT,
            expiresAt = System.currentTimeMillis() + 300_000L,
            missingSlots = ArrayDeque(listOf(SlotKey.TARGET_CONTACT, SlotKey.MESSAGE_BODY))
        )
        contact?.let { flow.fillSlot(SlotKey.TARGET_CONTACT, it) }
        flow.fillSlot(SlotKey.MESSAGE_CHANNEL, "sms")
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

    // ── Cancellation ──────────────────────────────────────────────────────────

    @Test
    fun `cancel that — classified as Cancellation`() {
        val flow = messageDraftFlow()
        val result = FollowUpResolver.classify("cancel that", flow, tracker)
        assertTrue(result is FollowUpResolver.Classification.Cancellation)
    }

    @Test
    fun `never mind — classified as Cancellation`() {
        val flow = messageDraftFlow()
        val result = FollowUpResolver.classify("never mind", flow, tracker)
        assertTrue(result is FollowUpResolver.Classification.Cancellation)
    }

    @Test
    fun `forget it — classified as Cancellation`() {
        val flow = messageDraftFlow()
        assertTrue(FollowUpResolver.classify("forget it", flow, tracker) is FollowUpResolver.Classification.Cancellation)
    }

    // ── Slot fills ────────────────────────────────────────────────────────────

    @Test
    fun `message body filled from plain text`() {
        val flow = messageDraftFlow(contact = "Chris")
        val result = FollowUpResolver.classify("I'm running late", flow, tracker)
        assertTrue(result is FollowUpResolver.Classification.SlotFill)
        val fill = result as FollowUpResolver.Classification.SlotFill
        assertEquals(SlotKey.MESSAGE_BODY, fill.slot)
        assertEquals("I'm running late", fill.value)
    }

    @Test
    fun `tell him resolves pronoun and fills message body`() {
        // Pre-load Chris as a known entity
        tracker.track(EntityReference(EntityType.CONTACT, label = "Chris"))

        val flow = messageDraftFlow(contact = "Chris")
        val result = FollowUpResolver.classify("tell him I'm running late", flow, tracker)
        assertTrue(result is FollowUpResolver.Classification.SlotFill)
        val fill = result as FollowUpResolver.Classification.SlotFill
        assertEquals(SlotKey.MESSAGE_BODY, fill.slot)
        assertTrue(fill.value.contains("running late"))
    }

    @Test
    fun `work fills phone type slot`() {
        val flow = ActiveFlow(
            type = FlowType.CALL_CONTACT,
            expiresAt = System.currentTimeMillis() + 300_000L,
            missingSlots = ArrayDeque(listOf(SlotKey.PHONE_TYPE))
        )
        flow.fillSlot(SlotKey.TARGET_CONTACT, "Sarah")
        flow.expectedSlot = SlotKey.PHONE_TYPE

        val result = FollowUpResolver.classify("work", flow, tracker)
        assertTrue(result is FollowUpResolver.Classification.SlotFill)
        val fill = result as FollowUpResolver.Classification.SlotFill
        assertEquals(SlotKey.PHONE_TYPE, fill.slot)
        assertEquals("work", fill.value)
    }

    @Test
    fun `in 20 minutes fills trigger time`() {
        val flow = reminderFlow(content = "check printer")
        flow.expectedSlot = SlotKey.TRIGGER_TIME

        val result = FollowUpResolver.classify("in 20 minutes", flow, tracker)
        assertTrue(result is FollowUpResolver.Classification.SlotFill)
        assertEquals(SlotKey.TRIGGER_TIME, (result as FollowUpResolver.Classification.SlotFill).slot)
    }

    @Test
    fun `reminder content filled from plain text`() {
        val flow = reminderFlow()
        flow.expectedSlot = SlotKey.REMINDER_CONTENT

        val result = FollowUpResolver.classify("check the printer", flow, tracker)
        assertTrue(result is FollowUpResolver.Classification.SlotFill)
        assertEquals(SlotKey.REMINDER_CONTENT, (result as FollowUpResolver.Classification.SlotFill).slot)
        assertEquals("check the printer", (result as FollowUpResolver.Classification.SlotFill).value)
    }

    // ── Corrections ───────────────────────────────────────────────────────────

    @Test
    fun `actually James — classified as Correction`() {
        val flow = messageDraftFlow(contact = "Chris")
        val result = FollowUpResolver.classify("actually James", flow, tracker)
        assertTrue(result is FollowUpResolver.Classification.Correction)
        assertEquals(SlotKey.TARGET_CONTACT, (result as FollowUpResolver.Classification.Correction).slot)
    }

    @Test
    fun `not Chris James — classified as Correction`() {
        val flow = messageDraftFlow(contact = "Chris")
        val result = FollowUpResolver.classify("not Chris, James", flow, tracker)
        assertTrue(result is FollowUpResolver.Classification.Correction)
    }

    // ── Confirmation ──────────────────────────────────────────────────────────

    @Test
    fun `yes when all slots filled — Confirmation`() {
        val flow = messageDraftFlow(contact = "Chris")
        flow.fillSlot(SlotKey.MESSAGE_BODY, "I'm running late")  // all slots done
        val result = FollowUpResolver.classify("yes", flow, tracker)
        assertTrue(result is FollowUpResolver.Classification.Confirmation)
    }

    // ── Unrelated ─────────────────────────────────────────────────────────────

    @Test
    fun `what time is it — classified as Unrelated`() {
        val flow = messageDraftFlow(contact = "Chris")
        val result = FollowUpResolver.classify("what time is it", flow, tracker)
        assertTrue(result is FollowUpResolver.Classification.Unrelated)
    }

    // ── Denial ───────────────────────────────────────────────────────────────

    @Test
    fun `no — classified as Denial`() {
        val flow = messageDraftFlow(contact = "Chris")
        val result = FollowUpResolver.classify("no", flow, tracker)
        assertTrue(result is FollowUpResolver.Classification.Denial)
    }
}
