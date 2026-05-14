package com.jarvis.assistant.tools.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallIntentParserTest {

    @Test
    fun `bare call defaults to phone voice`() {
        val r = CallIntentParser.parse("call Mike")!!
        assertEquals(CallIntentParser.Channel.PHONE, r.channel)
        assertEquals(CallIntentParser.Mode.VOICE,    r.mode)
        assertEquals("Mike", r.recipient)
    }

    @Test
    fun `'on WhatsApp' routes to WhatsApp without polluting the name`() {
        val r = CallIntentParser.parse("call Mike on WhatsApp")!!
        assertEquals(CallIntentParser.Channel.WHATSAPP, r.channel)
        assertEquals("Mike", r.recipient)
    }

    @Test
    fun `'via WhatsApp' also routes`() {
        val r = CallIntentParser.parse("ring Cath via whatsapp")!!
        assertEquals(CallIntentParser.Channel.WHATSAPP, r.channel)
        assertEquals("Cath", r.recipient)
    }

    @Test
    fun `'WhatsApp call X' routes WhatsApp`() {
        val r = CallIntentParser.parse("WhatsApp call Mike")!!
        assertEquals(CallIntentParser.Channel.WHATSAPP, r.channel)
        assertEquals("Mike", r.recipient)
    }

    @Test
    fun `video call hint sets mode VIDEO`() {
        val r = CallIntentParser.parse("video call Cath on WhatsApp")!!
        assertEquals(CallIntentParser.Channel.WHATSAPP, r.channel)
        assertEquals(CallIntentParser.Mode.VIDEO,        r.mode)
        assertEquals("Cath", r.recipient)
    }

    @Test
    fun `'facetime X on WhatsApp' is WhatsApp video`() {
        val r = CallIntentParser.parse("facetime Mike on WhatsApp")!!
        assertEquals(CallIntentParser.Channel.WHATSAPP, r.channel)
        assertEquals(CallIntentParser.Mode.VIDEO,        r.mode)
        assertEquals("Mike", r.recipient)
    }

    @Test
    fun `trailing courtesies are stripped from recipient`() {
        assertEquals("mum",  CallIntentParser.parse("call mum for me please")!!.recipient.lowercase())
        assertEquals("mike", CallIntentParser.parse("call Mike now")!!.recipient.lowercase())
    }

    @Test
    fun `multi-word names survive`() {
        val r = CallIntentParser.parse("call John Smith on WhatsApp")!!
        assertEquals("John Smith", r.recipient)
        assertEquals(CallIntentParser.Channel.WHATSAPP, r.channel)
    }

    @Test
    fun `non-call utterances return null`() {
        assertNull(CallIntentParser.parse("what's the weather"))
        assertNull(CallIntentParser.parse("send Mike a WhatsApp"))
    }

    @Test
    fun `looksLikeCallCommand catches all verbs`() {
        listOf("call X", "phone Mike", "ring Sam", "dial 999", "facetime Joe", "video call Dad")
            .forEach { assertEquals("'$it' should be a call cmd", true,
                CallIntentParser.looksLikeCallCommand(it)) }
        assertEquals(false, CallIntentParser.looksLikeCallCommand("what time is it"))
    }
}
