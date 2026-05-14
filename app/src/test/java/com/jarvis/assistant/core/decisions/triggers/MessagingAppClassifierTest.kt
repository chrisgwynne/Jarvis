package com.jarvis.assistant.core.decisions.triggers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingAppClassifierTest {

    @Test
    fun `whitelist covers the common chat apps`() {
        listOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "org.thoughtcrime.securesms",
            "org.telegram.messenger",
            "com.facebook.orca",
            "com.discord",
        ).forEach {
            assertTrue("$it should be classified as messaging",
                MessagingAppClassifier.isMessagingApp(it))
        }
    }

    @Test
    fun `non-messaging apps are not classified`() {
        listOf(
            "com.example.news",
            "com.android.chrome",
            "io.homeassistant.companion.android",
            "com.spotify.music",
        ).forEach {
            assertFalse("$it must not be classified as messaging",
                MessagingAppClassifier.isMessagingApp(it))
        }
    }

    @Test
    fun `null package is not messaging`() {
        assertFalse(MessagingAppClassifier.isMessagingApp(null))
        assertNull(MessagingAppClassifier.displayName(null))
    }

    @Test
    fun `display names are friendly`() {
        assertEquals("WhatsApp",  MessagingAppClassifier.displayName("com.whatsapp"))
        assertEquals("WhatsApp",  MessagingAppClassifier.displayName("com.whatsapp.w4b"))
        assertEquals("Signal",    MessagingAppClassifier.displayName("org.thoughtcrime.securesms"))
        assertEquals("Telegram",  MessagingAppClassifier.displayName("org.telegram.messenger"))
        assertEquals("Messenger", MessagingAppClassifier.displayName("com.facebook.orca"))
        assertEquals("Messages",  MessagingAppClassifier.displayName("com.google.android.apps.messaging"))
    }

    @Test
    fun `unknown packages return null display name`() {
        assertNull(MessagingAppClassifier.displayName("com.example.unknown"))
    }
}
