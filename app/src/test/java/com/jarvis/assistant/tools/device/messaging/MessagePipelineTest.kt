package com.jarvis.assistant.tools.device.messaging

import com.jarvis.assistant.tools.ContactLookup
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * MessagePipelineTest — proves SMS and WhatsApp share an identical
 * execution path.  Adapters are swappable mocks; the pipeline is the
 * single arbiter of latency, contact lookup, and slot validation.
 */
class MessagePipelineTest {

    private fun contactLookup(
        hit: ContactLookup.Contact? = ContactLookup.Contact("Mike Smith", "+447111111111"),
        disambiguation: String? = null
    ): ContactLookup {
        val cl: ContactLookup = mock()
        whenever(cl.find(any())).thenReturn(hit)
        whenever(cl.disambiguationPrompt()).thenReturn(disambiguation)
        return cl
    }

    private fun fakeAdapter(channel: String, willSucceed: Boolean = true,
                            spokenFeedback: String = "ok"): MessageDeliveryAdapter =
        object : MessageDeliveryAdapter {
            override val channelName = channel
            override suspend fun send(contact: ContactLookup.Contact, body: String) =
                if (willSucceed) ToolResult.Success(spokenFeedback)
                else ToolResult.Failure("adapter failed")
        }

    @Test fun `SMS happy path succeeds`() = runBlocking {
        val r = MessagePipeline.run(
            input    = ToolInput("send a message to mike saying hello",
                                 mapOf("name" to "Mike", "message" to "hello")),
            contacts = contactLookup(),
            adapter  = fakeAdapter("SMS", spokenFeedback = "Message sent to Mike Smith."),
        )
        assertTrue(r is ToolResult.Success)
        assertEquals("Message sent to Mike Smith.", (r as ToolResult.Success).spokenFeedback)
    }

    @Test fun `WhatsApp happy path succeeds via the same pipeline`() = runBlocking {
        val r = MessagePipeline.run(
            input    = ToolInput("send a whatsapp to mike saying hello",
                                 mapOf("name" to "Mike", "message" to "hello")),
            contacts = contactLookup(),
            adapter  = fakeAdapter("WHATSAPP",
                spokenFeedback = "Sending WhatsApp message to Mike Smith."),
        )
        assertTrue(r is ToolResult.Success)
        assertEquals("Sending WhatsApp message to Mike Smith.",
            (r as ToolResult.Success).spokenFeedback)
    }

    @Test fun `missing recipient asks local clarification`() = runBlocking {
        val r = MessagePipeline.run(
            input    = ToolInput("send a whatsapp saying hello",
                                 mapOf("name" to "", "message" to "hello")),
            contacts = contactLookup(),
            adapter  = fakeAdapter("WHATSAPP"),
        )
        assertTrue("Expected Failure with clarification, got $r", r is ToolResult.Failure)
        val msg = (r as ToolResult.Failure).spokenFeedback
        assertTrue("Prompt should mention 'Who'", msg.contains("Who", ignoreCase = true))
    }

    @Test fun `missing body asks local clarification`() = runBlocking {
        val r = MessagePipeline.run(
            input    = ToolInput("whatsapp mike",
                                 mapOf("name" to "Mike", "message" to "")),
            contacts = contactLookup(),
            adapter  = fakeAdapter("WHATSAPP"),
        )
        assertTrue("Expected Failure with clarification, got $r", r is ToolResult.Failure)
        val msg = (r as ToolResult.Failure).spokenFeedback
        assertTrue("Prompt should mention 'What' / 'say'",
            msg.contains("What", ignoreCase = true) ||
            msg.contains("say",  ignoreCase = true))
    }

    @Test fun `disambiguation prompt is surfaced when contact lookup is ambiguous`() = runBlocking {
        val r = MessagePipeline.run(
            input    = ToolInput("whatsapp chris hello",
                                 mapOf("name" to "Chris", "message" to "hello")),
            contacts = contactLookup(
                hit = null, disambiguation = "Did you mean Chris Smith or Chris Brown?"
            ),
            adapter  = fakeAdapter("WHATSAPP"),
        )
        assertTrue(r is ToolResult.Failure)
        assertTrue(((r as ToolResult.Failure).spokenFeedback).contains("Did you mean"))
    }

    @Test fun `no contact found returns a clear failure`() = runBlocking {
        val r = MessagePipeline.run(
            input    = ToolInput("whatsapp xyz hello",
                                 mapOf("name" to "Xyz", "message" to "hello")),
            contacts = contactLookup(hit = null),
            adapter  = fakeAdapter("WHATSAPP"),
        )
        assertTrue(r is ToolResult.Failure)
        val msg = (r as ToolResult.Failure).spokenFeedback
        assertTrue("Expected 'I can't find', got: $msg",
            msg.contains("can't find", ignoreCase = true))
    }

    @Test fun `slow adapter is bounded by the pipeline ceiling`() = runBlocking {
        // Test version: 50 ms ceiling rather than 3 s so this test stays fast.
        // We hit the same code path by passing a longer-delay adapter.
        val slow = object : MessageDeliveryAdapter {
            override val channelName = "WHATSAPP"
            override suspend fun send(contact: ContactLookup.Contact, body: String): ToolResult {
                delay(10_000)
                return ToolResult.Success("never spoken")
            }
        }
        val r = MessagePipeline.run(
            input    = ToolInput("whatsapp mike hello",
                                 mapOf("name" to "Mike", "message" to "hello")),
            contacts = contactLookup(),
            adapter  = slow,
        )
        // Pipeline cuts at 3 s — a real-time slow adapter must yield Failure.
        assertTrue("Expected Failure on slow adapter, got $r", r is ToolResult.Failure)
        val msg = (r as ToolResult.Failure).spokenFeedback
        // Failure copy varies by channel; just confirm it's a non-empty message.
        assertTrue("Failure should produce a user-visible message", msg.isNotBlank())
    }

    @Test fun `looksLikeMessagingCommand recognises whatsapp wa text and sms`() {
        val parser = com.jarvis.assistant.tools.device.MessageIntentParser
        assertTrue(parser.looksLikeMessagingCommand("whatsapp mike"))
        assertTrue(parser.looksLikeMessagingCommand("whats app mike"))
        assertTrue(parser.looksLikeMessagingCommand("wa mike hello"))
        assertTrue(parser.looksLikeMessagingCommand("send mike a message"))
        assertTrue(parser.looksLikeMessagingCommand("text mike"))
        assertTrue(parser.looksLikeMessagingCommand("send mike an sms"))
        // Negative
        assertEquals(false, parser.looksLikeMessagingCommand("turn on the lights"))
        assertEquals(false, parser.looksLikeMessagingCommand("what's the weather"))
    }
}
