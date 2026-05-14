package com.jarvis.assistant.tools.device

import com.jarvis.assistant.memory.ProfileMemoryService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Matcher-only tests for [PersonalFactTool].  Verifies that identity
 * questions are caught at the matcher level so they never fall through
 * to the LLM.
 */
class PersonalFactToolMatcherTest {

    private val tool = PersonalFactTool(profile = mock<ProfileMemoryService>())

    @Test fun `what's my name matches`() {
        assertNotNull(tool.matches("what's my name"))
        assertNotNull(tool.matches("what is my name"))
        assertNotNull(tool.matches("WHAT'S MY NAME"))
    }

    @Test fun `who am i matches`() {
        assertNotNull(tool.matches("who am I"))
    }

    @Test fun `remind me of my name matches`() {
        assertNotNull(tool.matches("remind me of my name"))
        assertNotNull(tool.matches("do you know my name"))
        assertNotNull(tool.matches("do you remember my name"))
    }

    @Test fun `where do i live matches`() {
        assertNotNull(tool.matches("where do I live"))
        assertNotNull(tool.matches("what's my address"))
    }

    @Test fun `where am i from matches`() {
        assertNotNull(tool.matches("where am I from"))
    }

    @Test fun `how old am i matches`() {
        assertNotNull(tool.matches("how old am I"))
        assertNotNull(tool.matches("what's my age"))
    }

    @Test fun `what do i do matches as JOB`() {
        assertNotNull(tool.matches("what do I do for work"))
        assertNotNull(tool.matches("what's my job"))
        assertNotNull(tool.matches("what is my profession"))
    }

    @Test fun `when's my birthday matches`() {
        assertNotNull(tool.matches("when's my birthday"))
        assertNotNull(tool.matches("what's my birthday"))
    }

    @Test fun `unrelated does not match`() {
        assertNull(tool.matches("send a whatsapp to mike"))
        assertNull(tool.matches("turn on the lights"))
        assertNull(tool.matches("what's the weather"))
        // Different person's name → not "my name"
        assertNull(tool.matches("what's Mike's name"))
        // Generic memory recall → goes to MemoryRecallTool
        assertNull(tool.matches("do you remember what I said yesterday"))
    }

    @Test fun `the matched ToolInput carries the kind`() {
        val input = tool.matches("what's my name")
        assertNotNull(input)
        assertEquals("NAME", input!!.param("kind"))
    }
}
