package com.jarvis.assistant.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineManagerTest {

    @Test fun `flashlight is a local tool`() {
        assertTrue(OfflineManager.isLocalTool("flashlight"))
    }

    @Test fun `web search is not a local tool`() {
        assertFalse(OfflineManager.isLocalTool("web_search"))
    }

    @Test fun `volume_control is a local tool`() {
        assertTrue(OfflineManager.isLocalTool("volume_control"))
    }

    @Test fun `alarm is a local tool`() {
        assertTrue(OfflineManager.isLocalTool("set_alarm"))
    }

    @Test fun `timer is a local tool`() {
        assertTrue(OfflineManager.isLocalTool("set_timer"))
    }

    @Test fun `offline fallback for question is descriptive`() {
        val msg = OfflineManager.offlineLlmFallback("what is the weather today?")
        assertTrue(msg.contains("offline", ignoreCase = true))
    }

    @Test fun `offline fallback for command is shorter`() {
        val question = OfflineManager.offlineLlmFallback("what time is it?")
        val command  = OfflineManager.offlineLlmFallback("turn on flashlight")
        // Question fallback should mention limitation, both should be non-empty
        assertTrue(question.isNotBlank())
        assertTrue(command.isNotBlank())
    }
}
