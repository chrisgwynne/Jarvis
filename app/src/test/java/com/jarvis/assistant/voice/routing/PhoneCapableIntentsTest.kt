package com.jarvis.assistant.voice.routing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCapableIntentsTest {

    // ── Phone-capable utterances ───────────────────────────────────────────

    @Test
    fun `volume control is phone-capable`() {
        listOf(
            "turn the volume down",
            "turn volume up",
            "mute the phone",
            "unmute",
        ).forEach {
            assertTrue("'$it' should be phone-capable",
                PhoneCapableIntents.looksPhoneCapable(it))
        }
    }

    @Test
    fun `device controls are phone-capable`() {
        listOf(
            "turn flashlight on",
            "turn torch off",
            "turn bluetooth on",
            "turn wifi off",
            "turn on do not disturb",
            "how much battery do i have",
        ).forEach {
            assertTrue("'$it' should be phone-capable",
                PhoneCapableIntents.looksPhoneCapable(it))
        }
    }

    @Test
    fun `app launching is phone-capable`() {
        listOf("open spotify", "open whatsapp", "open maps", "take me to settings")
            .forEach {
                assertTrue("'$it' should be phone-capable",
                    PhoneCapableIntents.looksPhoneCapable(it))
            }
    }

    @Test
    fun `calls and messaging are phone-capable`() {
        listOf(
            "call mike",
            "ring mum",
            "hang up",
            "send whatsapp to mike saying hello",
            "text cath I'm on my way",
        ).forEach {
            assertTrue("'$it' should be phone-capable",
                PhoneCapableIntents.looksPhoneCapable(it))
        }
    }

    @Test
    fun `reminders and tasks are phone-capable`() {
        listOf(
            "remind me to take bins out tomorrow at 7",
            "set a reminder for 8pm",
            "todo call mike",
            "don't let me forget to order filament",
            "I need to remember to pay the invoice friday",
        ).forEach {
            assertTrue("'$it' should be phone-capable",
                PhoneCapableIntents.looksPhoneCapable(it))
        }
    }

    @Test
    fun `calendar and maps are phone-capable`() {
        listOf(
            "what's on my calendar today",
            "what's my next event",
            "navigate home",
            "take me to tesco",
            "how long to liverpool",
            "where am i",
        ).forEach {
            assertTrue("'$it' should be phone-capable",
                PhoneCapableIntents.looksPhoneCapable(it))
        }
    }

    @Test
    fun `camera and media are phone-capable`() {
        listOf(
            "take a photo",
            "take a selfie",
            "take a screenshot",
            "start recording",
        ).forEach {
            assertTrue("'$it' should be phone-capable",
                PhoneCapableIntents.looksPhoneCapable(it))
        }
    }

    @Test
    fun `alarms timers and notifications are phone-capable`() {
        listOf(
            "set a timer for 10 minutes",
            "set an alarm for 7am",
            "cancel my timer",
            "read my notifications",
            "clear notifications",
        ).forEach {
            assertTrue("'$it' should be phone-capable",
                PhoneCapableIntents.looksPhoneCapable(it))
        }
    }

    @Test
    fun `home assistant controls are phone-capable`() {
        listOf(
            "turn on kitchen lights",
            "turn off the lamp",
            "turn on the fan",
            "turn off the kettle",
        ).forEach {
            assertTrue("'$it' should be phone-capable",
                PhoneCapableIntents.looksPhoneCapable(it))
        }
    }

    @Test
    fun `time and date queries are phone-capable`() {
        listOf("what time is it", "what's the time", "what's the date", "what is today's date")
            .forEach {
                assertTrue("'$it' should be phone-capable",
                    PhoneCapableIntents.looksPhoneCapable(it))
            }
    }

    // ── Non-phone-capable utterances (should escalate) ─────────────────────

    @Test
    fun `complex queries are NOT phone-capable`() {
        listOf(
            "help me price my etsy listings",
            "write a short story about a robot",
            "explain the difference between rust and go",
            "research the best photo slates uk",
            "summarise the latest news",
        ).forEach {
            assertFalse("'$it' should NOT be phone-capable",
                PhoneCapableIntents.looksPhoneCapable(it))
        }
    }

    @Test
    fun `blank transcript is not phone-capable`() {
        assertFalse(PhoneCapableIntents.looksPhoneCapable(""))
        assertFalse(PhoneCapableIntents.looksPhoneCapable("   "))
    }

    // ── isInvalidRemoteRoute returns true for phone-capable ────────────────

    @Test
    fun `isInvalidRemoteRoute flags phone-capable transcripts`() {
        assertTrue(PhoneCapableIntents.isInvalidRemoteRoute(
            "turn the volume down", remoteSubsystem = "openclaw"))
        assertTrue(PhoneCapableIntents.isInvalidRemoteRoute(
            "remind me to take bins out tomorrow", remoteSubsystem = "hermes"))
    }

    @Test
    fun `isInvalidRemoteRoute permits genuinely complex queries`() {
        assertFalse(PhoneCapableIntents.isInvalidRemoteRoute(
            "help me price my etsy listings", remoteSubsystem = "openclaw"))
        assertFalse(PhoneCapableIntents.isInvalidRemoteRoute(
            "summarise this article", remoteSubsystem = "openclaw"))
    }
}
