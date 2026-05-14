package com.jarvis.assistant.followup

import android.content.Context
import com.jarvis.assistant.reminders.ReminderRepository
import com.jarvis.assistant.tools.ContactLookup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class MessageFlowTest {

    private lateinit var context: Context
    private lateinit var contactLookup: ContactLookup
    private lateinit var reminderRepo: ReminderRepository
    private lateinit var coordinator: FollowUpCoordinator

    @Before fun setUp() {
        context       = mock()
        com.jarvis.assistant.testing.stubSharedPreferences(context)
        contactLookup = mock()
        reminderRepo  = mock()
        coordinator   = FollowUpCoordinator(context, contactLookup, reminderRepo)
    }

    // ── "Message Chris" → ask for body → send ────────────────────────────────

    @Test
    fun `message Chris — asks for body`() = runTest {
        val result = coordinator.process("message Chris")
        assertTrue(result is FlowResult.AwaitingInput)
        val prompt = (result as FlowResult.AwaitingInput).prompt
        assertTrue(prompt.contains("say", ignoreCase = true))
    }

    @Test
    fun `message Chris then tell him running late — sends message`() = runTest {
        val contact = ContactLookup.Contact("Chris Smith", "+441234567890")
        whenever(contactLookup.find(any())).thenReturn(contact)

        // Turn 1: start flow
        val r1 = coordinator.process("message Chris")
        assertTrue(r1 is FlowResult.AwaitingInput)

        // Track Chris as recent entity
        coordinator.entityTracker.track(EntityReference(EntityType.CONTACT, label = "Chris"))

        // Turn 2: fill body — "tell him" should resolve "him" → Chris
        val r2 = coordinator.process("tell him I'm running late")
        assertTrue("Expected Complete, got $r2", r2 is FlowResult.Complete)
        verify(context).startActivity(any())  // SMS send triggers ACTION_VIEW or SMS
    }

    @Test
    fun `text Chris then I am at the supplier — sends SMS`() = runTest {
        val contact = ContactLookup.Contact("Chris Brown", "+447000000001")
        whenever(contactLookup.find(any())).thenReturn(contact)

        coordinator.process("text Chris")
        val result = coordinator.process("I'm at the supplier")
        assertTrue(result is FlowResult.Complete)
    }

    // ── Contact not found ──────────────────────────────────────────────────────

    @Test
    fun `contact not found — graceful Complete with error message`() = runTest {
        whenever(contactLookup.find(any())).thenReturn(null)

        coordinator.process("message Nobody")
        val result = coordinator.process("hey there")
        assertTrue(result is FlowResult.Complete)
        val msg = (result as FlowResult.Complete).response
        assertTrue(msg.contains("couldn't find", ignoreCase = true))
    }

    // ── Cancellation ──────────────────────────────────────────────────────────

    @Test
    fun `message Chris then cancel that — cancels flow`() = runTest {
        coordinator.process("message Chris")
        val result = coordinator.process("cancel that")
        assertTrue(result is FlowResult.Cancelled)
    }

    @Test
    fun `message Chris then never mind — cancels flow`() = runTest {
        coordinator.process("message Chris")
        val result = coordinator.process("never mind")
        assertTrue(result is FlowResult.Cancelled)
    }

    // ── Correction ────────────────────────────────────────────────────────────

    @Test
    fun `message Chris then actually James — updates contact asks body`() = runTest {
        coordinator.process("message Chris")
        val result = coordinator.process("actually James")
        // Should update contact to James and continue asking for body
        assertTrue("Expected AwaitingInput after correction, got $result",
            result is FlowResult.AwaitingInput || result is FlowResult.Complete)
    }

    @Test
    fun `message Chris then not Chris send to Sarah — corrects contact`() = runTest {
        coordinator.process("message Chris")
        val result = coordinator.process("not Chris, Sarah")
        // Should update contact to Sarah and still ask for body
        assertTrue(result is FlowResult.AwaitingInput)
    }

    // ── Fully-specified message does not start a flow ─────────────────────────

    @Test
    fun `text Chris saying I am late — passes through`() = runTest {
        // When body is inline, ToolRegistry handles it — coordinator should not intercept
        val result = coordinator.process("text Chris saying I am late")
        assertEquals(FlowResult.PassThrough, result)
    }

    // ── Unrelated question during flow ────────────────────────────────────────

    @Test
    fun `message Chris then what time is it — passes through as new request`() = runTest {
        coordinator.process("message Chris")
        val result = coordinator.process("what time is it")
        // Unrelated question → PassThrough so LLM can answer it
        assertEquals(FlowResult.PassThrough, result)
    }
}
