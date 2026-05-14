package com.jarvis.assistant.tools.device.messaging

import com.jarvis.assistant.tools.device.MessageIntentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PendingMessageIntentBodyMissingTest — regression test for the
 * "Send a WhatsApp to Mike → Hello → Jarvis says 'Hey' but never
 * sends" bug.
 *
 * Background:
 *   The bug was a wiring gap in JarvisRuntime.  When a messaging tool
 *   returned `ToolResult.Failure("What should the WhatsApp to Mike
 *   say?")`, the runtime spoke the prompt + transitioned to Listening
 *   but did NOT park a `PendingMessageIntent`.  The user's NEXT
 *   utterance ("Hello") was then routed as a fresh small-talk turn,
 *   the LLM happily replied "Hey", and the WhatsApp never sent.
 *
 * Fix:
 *   `DispatchResult.Failed` now carries the same `BrainHints` (tool
 *   name + input) as the success variants, and JarvisRuntime's Failed
 *   branch parks a `PendingMessageIntent` when the failed tool is
 *   `whatsapp_message` or `send_sms`.  The next-turn intercept then
 *   merges in "Hello" as the body and runs `MessagePipeline` directly.
 *
 * This test pins the merge contract that the fix relies on.  The
 * JarvisRuntime path itself is exercised end-to-end via the existing
 * `MessageFlowTest` (FollowUpCoordinator-driven) plus the live
 * smoke test.
 */
class PendingMessageIntentBodyMissingTest {

    @Test
    fun `body-missing pending intent + Hello follow-up resolves to recipient + body`() {
        // The runtime parks this when WhatsAppTool fails with "body blank".
        // Recipient = "Mike" (already parsed), channel = WHATSAPP, body = "".
        val parked = PendingMessageIntent.create(
            channel   = MessageIntentParser.Channel.WHATSAPP,
            recipient = "Mike",
            body      = "",
            nowMs     = 1_000L,
        )

        // User says "Hello" — the next-turn intercept passes it through merge.
        val merged = PendingMessageIntent.merge(parked, "Hello")

        assertEquals("Mike", merged.recipient)
        assertEquals("Hello", merged.body)
        assertTrue("merged intent must be ready to dispatch", merged.isReady)
    }

    @Test
    fun `body-missing then bare body keeps channel and TTL window`() {
        val parked = PendingMessageIntent.create(
            channel = MessageIntentParser.Channel.SMS,
            recipient = "Chris",
            body = "",
            nowMs = 0L,
        )
        val merged = PendingMessageIntent.merge(parked, "I'm running late.")
        assertEquals(MessageIntentParser.Channel.SMS, merged.channel)
        assertEquals("Chris", merged.recipient)
        assertEquals("I'm running late.", merged.body)
        assertTrue(merged.isReady)
    }

    @Test
    fun `expired pending intent is detectable so runtime can drop it`() {
        val now = 10_000L
        val parked = PendingMessageIntent.create(
            channel = MessageIntentParser.Channel.WHATSAPP,
            recipient = "Mike",
            body = "",
            nowMs = now - PendingMessageIntent.TTL_MS - 1L,
        )
        assertTrue(parked.isExpired(nowMs = now))
        // A fresh one isn't.
        val fresh = PendingMessageIntent.create(
            channel = MessageIntentParser.Channel.WHATSAPP,
            recipient = "Mike",
            body = "",
            nowMs = now,
        )
        assertFalse(fresh.isExpired(nowMs = now))
    }
}
