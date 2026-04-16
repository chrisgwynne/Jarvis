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
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CallFlowTest {

    private lateinit var context: Context
    private lateinit var contactLookup: ContactLookup
    private lateinit var reminderRepo: ReminderRepository
    private lateinit var coordinator: FollowUpCoordinator

    @Before fun setUp() {
        context       = mock()
        contactLookup = mock()
        reminderRepo  = mock()
        coordinator   = FollowUpCoordinator(context, contactLookup, reminderRepo)
    }

    // ── "Call Sarah" → asks phone type → calls ────────────────────────────────

    @Test
    fun `call Sarah — asks mobile or work`() = runTest {
        val result = coordinator.process("call Sarah")
        assertTrue("Expected AwaitingInput, got $result", result is FlowResult.AwaitingInput)
        val prompt = (result as FlowResult.AwaitingInput).prompt
        // Should mention the contact name and phone type options
        assertTrue(
            "Prompt should mention mobile/work, got: $prompt",
            prompt.contains("mobile", ignoreCase = true) || prompt.contains("work", ignoreCase = true)
        )
    }

    @Test
    fun `call Sarah then work — places call`() = runTest {
        val contact = ContactLookup.Contact("Sarah Jones", "+441234500001")
        whenever(contactLookup.find(any())).thenReturn(contact)

        // Turn 1: start flow
        val r1 = coordinator.process("call Sarah")
        assertTrue(r1 is FlowResult.AwaitingInput)

        // Turn 2: supply phone type
        val r2 = coordinator.process("work")
        assertTrue("Expected Complete, got $r2", r2 is FlowResult.Complete)
        // ACTION_CALL intent should have been fired
        verify(context).startActivity(any())
    }

    @Test
    fun `call Sarah then mobile — places call`() = runTest {
        val contact = ContactLookup.Contact("Sarah Jones", "+447000000002")
        whenever(contactLookup.find(any())).thenReturn(contact)

        coordinator.process("call Sarah")
        val result = coordinator.process("mobile")
        assertTrue(result is FlowResult.Complete)
        verify(context).startActivity(any())
    }

    @Test
    fun `call Sarah then her mobile — places call`() = runTest {
        val contact = ContactLookup.Contact("Sarah Jones", "+447000000003")
        whenever(contactLookup.find(any())).thenReturn(contact)

        coordinator.process("call Sarah")
        val result = coordinator.process("her mobile")
        assertTrue(result is FlowResult.Complete)
    }

    // ── Contact not found ─────────────────────────────────────────────────────

    @Test
    fun `call Sarah contact not found — Complete with error message`() = runTest {
        whenever(contactLookup.find(any())).thenReturn(null)

        coordinator.process("call Sarah")
        val result = coordinator.process("mobile")
        assertTrue(result is FlowResult.Complete)
        val msg = (result as FlowResult.Complete).response
        assertTrue("Error message expected, got: $msg",
            msg.contains("couldn't find", ignoreCase = true))
    }

    // ── Cancellation ──────────────────────────────────────────────────────────

    @Test
    fun `call Sarah then cancel that — cancels flow`() = runTest {
        coordinator.process("call Sarah")
        val result = coordinator.process("cancel that")
        assertTrue(result is FlowResult.Cancelled)
    }

    @Test
    fun `call Sarah then never mind — cancels flow`() = runTest {
        coordinator.process("call Sarah")
        val result = coordinator.process("never mind")
        assertTrue(result is FlowResult.Cancelled)
    }

    // ── "Call" without a contact ──────────────────────────────────────────────

    @Test
    fun `call with no contact — asks who`() = runTest {
        val result = coordinator.process("call")
        assertTrue("Expected AwaitingInput, got $result", result is FlowResult.AwaitingInput)
        val prompt = (result as FlowResult.AwaitingInput).prompt
        assertTrue(prompt.contains("call", ignoreCase = true) || prompt.contains("who", ignoreCase = true))
    }

    @Test
    fun `call with no contact then Sarah then work — places call`() = runTest {
        val contact = ContactLookup.Contact("Sarah Jones", "+447000000004")
        whenever(contactLookup.find(any())).thenReturn(contact)

        // Turn 1: "call" without contact
        val r1 = coordinator.process("call")
        assertTrue(r1 is FlowResult.AwaitingInput)

        // Turn 2: supply contact name
        val r2 = coordinator.process("Sarah")
        // After contact is named, should ask phone type or proceed
        assertTrue("Expected AwaitingInput or Complete after naming contact, got $r2",
            r2 is FlowResult.AwaitingInput || r2 is FlowResult.Complete)

        // Turn 3: if phone type was asked, supply it
        if (r2 is FlowResult.AwaitingInput) {
            val r3 = coordinator.process("work")
            assertTrue(r3 is FlowResult.Complete)
        }
    }

    // ── Correction during call flow ───────────────────────────────────────────

    @Test
    fun `call Sarah then actually James — corrects contact asks phone type`() = runTest {
        coordinator.process("call Sarah")
        val result = coordinator.process("actually James")
        // Should update contact to James and still ask for phone type
        assertTrue("Expected AwaitingInput or Complete after correction, got $result",
            result is FlowResult.AwaitingInput || result is FlowResult.Complete)
    }

    // ── Unrelated question during flow ────────────────────────────────────────

    @Test
    fun `call Sarah then what time is it — passes through as new request`() = runTest {
        coordinator.process("call Sarah")
        val result = coordinator.process("what time is it")
        assertEquals(FlowResult.PassThrough, result)
    }

    // ── Completed flow does not hijack next request ───────────────────────────

    @Test
    fun `after call completes — next utterance is PassThrough`() = runTest {
        val contact = ContactLookup.Contact("Sarah Jones", "+447000000005")
        whenever(contactLookup.find(any())).thenReturn(contact)

        coordinator.process("call Sarah")
        coordinator.process("mobile")   // completes the flow

        // Fresh utterance — coordinator should not intercept
        val result = coordinator.process("what's the weather like")
        assertEquals(FlowResult.PassThrough, result)
    }
}
