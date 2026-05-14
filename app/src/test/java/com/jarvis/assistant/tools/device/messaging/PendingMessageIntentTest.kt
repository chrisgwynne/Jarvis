package com.jarvis.assistant.tools.device.messaging

import com.jarvis.assistant.tools.device.MessageIntentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingMessageIntentTest {

    @Test fun `freshly created intent is not ready and not expired`() {
        val p = PendingMessageIntent.create(MessageIntentParser.Channel.WHATSAPP)
        assertFalse(p.isReady)
        assertFalse(p.isExpired())
    }

    @Test fun `merge with 'Mike saying hello' fills both slots`() {
        val p = PendingMessageIntent.create(MessageIntentParser.Channel.WHATSAPP)
        val merged = PendingMessageIntent.merge(p, "Mike saying hello")
        assertEquals("Mike", merged.recipient)
        assertEquals("hello", merged.body)
        assertTrue(merged.isReady)
    }

    @Test fun `merge with 'Mike' only fills recipient`() {
        val p = PendingMessageIntent.create(MessageIntentParser.Channel.WHATSAPP)
        val merged = PendingMessageIntent.merge(p, "Mike")
        assertEquals("Mike", merged.recipient)
        assertEquals("", merged.body)
        assertFalse(merged.isReady)
    }

    @Test fun `merge with 'Mike hello' splits on whitespace`() {
        val p = PendingMessageIntent.create(MessageIntentParser.Channel.WHATSAPP)
        val merged = PendingMessageIntent.merge(p, "Mike hello")
        assertEquals("Mike", merged.recipient)
        assertEquals("hello", merged.body)
        assertTrue(merged.isReady)
    }

    @Test fun `merge preserves existing recipient when follow-up adds only body`() {
        val p = PendingMessageIntent.create(
            channel = MessageIntentParser.Channel.WHATSAPP,
            recipient = "Cath"
        )
        val merged = PendingMessageIntent.merge(p, "saying I'm running late")
        assertEquals("Cath", merged.recipient)
        assertEquals("I'm running late", merged.body)
        assertTrue(merged.isReady)
    }

    @Test fun `SMS channel mergers work the same way`() {
        val p = PendingMessageIntent.create(MessageIntentParser.Channel.SMS)
        val merged = PendingMessageIntent.merge(p, "Mike saying hello")
        assertEquals(MessageIntentParser.Channel.SMS, merged.channel)
        assertEquals("Mike", merged.recipient)
        assertEquals("hello", merged.body)
    }

    @Test fun `expired pending reports isExpired`() {
        val now = 1_000_000L
        val p = PendingMessageIntent.create(MessageIntentParser.Channel.WHATSAPP, nowMs = now)
        // Way past TTL.
        assertTrue(p.isExpired(now + PendingMessageIntent.TTL_MS + 1))
        assertFalse(p.isExpired(now + 1_000))
    }

    @Test fun `merge with empty follow-up returns pending unchanged`() {
        val p = PendingMessageIntent.create(
            MessageIntentParser.Channel.WHATSAPP, recipient = "Mike"
        )
        val merged = PendingMessageIntent.merge(p, "   ")
        assertEquals("Mike", merged.recipient)
        assertEquals("", merged.body)
    }
}
