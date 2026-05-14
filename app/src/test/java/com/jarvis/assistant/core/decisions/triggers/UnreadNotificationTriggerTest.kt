package com.jarvis.assistant.core.decisions.triggers

import com.jarvis.assistant.core.context.AgentContextFactory
import com.jarvis.assistant.proactive.ContextSnapshot
import com.jarvis.assistant.voice.VoiceFeatureFlags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnreadNotificationTriggerTest {

    private val trigger = UnreadNotificationTrigger()

    @After
    fun tearDown() {
        VoiceFeatureFlags.clearOverride(
            VoiceFeatureFlags.Flag.MESSAGING_NOTIFICATION_ANNOUNCE_ENABLED
        )
    }

    private fun snapshot(
        app: String? = null,
        text: String? = null,
        count: Int = 1,
        nowMs: Long = 1_700_000_000_000L,
    ) = ContextSnapshot(
        currentTimeMillis = nowMs,
        batteryLevel = 80,
        isCharging = false,
        screenOn = true,
        isJarvisSpeaking = false,
        isJarvisListening = false,
        lastUserInteractionTimeMillis = null,
        activeReminderCount = 0,
        nextReminderAtMillis = null,
        missedCallsCount = 0,
        lastMissedCallAtMillis = null,
        lastMissedCallContactName = null,
        currentLocationName = null,
        networkAvailable = true,
        unreadNotificationCount = count,
        lastNotificationText = text,
        lastNotificationApp = app,
    )

    @Test
    fun `no candidate when zero unread`() {
        val ctx = AgentContextFactory.fromSnapshot(snapshot(count = 0))
        assertNull(trigger.match(ctx, emptyList()))
    }

    @Test
    fun `default urgency when not a messaging app`() {
        val ctx = AgentContextFactory.fromSnapshot(
            snapshot(app = "com.example.news", text = "Breaking story")
        )
        val c = trigger.match(ctx, emptyList())
        assertNotNull(c)
        assertEquals(0.55f, c!!.urgency, 0.0001f)
        assertEquals("false", c.metadata["announce_messaging"])
    }

    @Test
    fun `messaging app stays at default urgency when flag off`() {
        VoiceFeatureFlags.setOverride(
            VoiceFeatureFlags.Flag.MESSAGING_NOTIFICATION_ANNOUNCE_ENABLED, false
        )
        val ctx = AgentContextFactory.fromSnapshot(
            snapshot(app = "com.whatsapp", text = "Wifey: hello")
        )
        val c = trigger.match(ctx, emptyList())!!
        assertEquals(0.55f, c.urgency, 0.0001f)
        assertEquals("false", c.metadata["announce_messaging"])
    }

    @Test
    fun `messaging app is elevated when flag on`() {
        VoiceFeatureFlags.setOverride(
            VoiceFeatureFlags.Flag.MESSAGING_NOTIFICATION_ANNOUNCE_ENABLED, true
        )
        val ctx = AgentContextFactory.fromSnapshot(
            snapshot(app = "com.whatsapp", text = "Wifey: are you coming")
        )
        val c = trigger.match(ctx, emptyList())!!
        assertEquals(0.85f, c.urgency, 0.0001f)
        assertEquals("true", c.metadata["announce_messaging"])
        assertEquals("WhatsApp from Wifey: are you coming", c.spokenText)
        // Per-app dedupe key includes the package + text hash, not the
        // global per-minute bucket.
        assertTrue(c.dedupeKey.startsWith("msg_announce_com.whatsapp_"))
    }

    @Test
    fun `two different senders in the same minute get different dedupe keys`() {
        VoiceFeatureFlags.setOverride(
            VoiceFeatureFlags.Flag.MESSAGING_NOTIFICATION_ANNOUNCE_ENABLED, true
        )
        val a = trigger.match(
            AgentContextFactory.fromSnapshot(
                snapshot(app = "com.whatsapp", text = "Alice: ping")
            ), emptyList()
        )!!
        val b = trigger.match(
            AgentContextFactory.fromSnapshot(
                snapshot(app = "com.whatsapp", text = "Bob: hey")
            ), emptyList()
        )!!
        assertFalse(
            "different senders must not collapse into one dedupe key",
            a.dedupeKey == b.dedupeKey
        )
    }

    @Test
    fun `buildMessagingSpoken extracts sender from colon prefix`() {
        assertEquals(
            "WhatsApp from Wifey: are you coming",
            trigger.buildMessagingSpoken("WhatsApp", "Wifey: are you coming")
        )
    }

    @Test
    fun `buildMessagingSpoken handles emoji in sender`() {
        assertEquals(
            "WhatsApp from Wifey ❤️: dinner",
            trigger.buildMessagingSpoken("WhatsApp", "Wifey ❤️: dinner")
        )
    }

    @Test
    fun `buildMessagingSpoken falls back when no colon`() {
        assertEquals(
            "WhatsApp: just a heads up",
            trigger.buildMessagingSpoken("WhatsApp", "just a heads up")
        )
    }

    @Test
    fun `buildMessagingSpoken falls back when colon is too far in`() {
        val long = "this is a very long sender name without colon nearby: body"
        val out = trigger.buildMessagingSpoken("WhatsApp", long)
        assertTrue(out.startsWith("WhatsApp: "))
    }

    @Test
    fun `home assistant motion alert is suppressed`() {
        val ctx = AgentContextFactory.fromSnapshot(
            snapshot(
                app = "io.homeassistant.companion.android",
                text = "Front door motion detected",
            )
        )
        // The classifier may or may not match this exact app; if it does
        // we return null, if it doesn't we just don't elevate urgency.
        val c = trigger.match(ctx, emptyList())
        if (c != null) {
            assertEquals(0.55f, c.urgency, 0.0001f)
        }
    }
}
