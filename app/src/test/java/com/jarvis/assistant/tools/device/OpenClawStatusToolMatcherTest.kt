package com.jarvis.assistant.tools.device

import com.jarvis.assistant.remote.openclaw.OpenClawSettingsRepository
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Matcher-only tests for [OpenClawStatusTool].  The execute() path is
 * exercised through [com.jarvis.assistant.remote.openclaw.OpenClawHealthMonitor]
 * which talks HTTP — out of scope for a JVM unit test, covered manually.
 */
class OpenClawStatusToolMatcherTest {

    private val tool = OpenClawStatusTool(openClawRepo = mock())

    @Test fun `connect to openclaw matches`() {
        assertNotNull(tool.matches("can you connect to OpenClaw"))
    }

    @Test fun `is openclaw connected matches`() {
        assertNotNull(tool.matches("is OpenClaw connected"))
    }

    @Test fun `openclaw status matches`() {
        assertNotNull(tool.matches("OpenClaw status"))
    }

    @Test fun `ping openclaw matches`() {
        assertNotNull(tool.matches("ping OpenClaw"))
    }

    @Test fun `test openclaw matches`() {
        assertNotNull(tool.matches("test OpenClaw"))
    }

    @Test fun `space variant 'open claw' matches`() {
        assertNotNull(tool.matches("connect to open claw"))
    }

    @Test fun `hyphen variant 'open-claw' matches`() {
        assertNotNull(tool.matches("test open-claw"))
    }

    @Test fun `whats openclaw matches`() {
        assertNotNull(tool.matches("what's OpenClaw"))
        assertNotNull(tool.matches("what is OpenClaw"))
    }

    @Test fun `unrelated transcript does not match`() {
        assertNull(tool.matches("send a whatsapp to mike"))
        assertNull(tool.matches("turn on the lights"))
        assertNull(tool.matches("what's the weather"))
    }

    @Test fun `case insensitive`() {
        assertNotNull(tool.matches("ARE YOU CONNECTED TO OPENCLAW"))
        assertNotNull(tool.matches("ping openclaw"))
        assertNotNull(tool.matches("PiNg OpEnClaw"))
    }
}
