package com.jarvis.assistant.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseFormatterTest {

    @Test fun `plain text is returned unchanged within limits`() {
        val input = "The time is 3pm. You have 80% battery. Looking good."
        val result = ResponseFormatter.format(input)
        assertEquals(input, result)
    }

    @Test fun `strips bold markdown`() {
        val result = ResponseFormatter.format("The answer is **yes**.")
        assertFalse(result.contains("**"))
        assertTrue(result.contains("yes"))
    }

    @Test fun `strips italic markdown`() {
        val result = ResponseFormatter.format("It is *very* important.")
        assertFalse(result.contains("*"))
        assertTrue(result.contains("very"))
    }

    @Test fun `strips heading markdown`() {
        val result = ResponseFormatter.format("# Hello\nThis is the body.")
        assertFalse(result.contains("#"))
    }

    @Test fun `strips bullet points`() {
        val result = ResponseFormatter.format("- Item one\n- Item two")
        assertFalse(result.contains("- "))
    }

    @Test fun `strips fenced code blocks`() {
        val result = ResponseFormatter.format("```\nsome code\n```\nHere is the explanation.")
        assertFalse(result.contains("```"))
    }

    @Test fun `caps at MAX_SENTENCES (3)`() {
        val fourSentences = "First sentence. Second sentence. Third sentence. Fourth sentence."
        val result = ResponseFormatter.format(fourSentences)
        val sentenceCount = result.count { it == '.' }
        assertTrue("Expected ≤3 sentences, got: $result", sentenceCount <= 3)
    }

    @Test fun `hard char cap applied`() {
        val longText = "a".repeat(400) + "."
        val result = ResponseFormatter.format(longText)
        assertTrue("Result too long: ${result.length}", result.length <= 320)
    }

    @Test fun `fallback to raw if stripped text is blank`() {
        // Entirely code block content
        val input = "```kotlin\nval x = 1\n```"
        val result = ResponseFormatter.format(input)
        assertTrue(result.isNotBlank())
    }

    @Test fun `normalises extra whitespace`() {
        val result = ResponseFormatter.format("Hello   world.  How   are   you.")
        assertFalse(result.contains("  "))
    }

    // ── Chain-of-thought stripping ───────────────────────────────────────────

    @Test fun `strips thinking tag block`() {
        val raw = "<thinking>The user is asking about X. Let me consider...</thinking>The answer is 42."
        val result = ResponseFormatter.format(raw)
        assertFalse("Output still contains thinking content: $result", result.contains("user is asking", ignoreCase = true))
        assertTrue("Real answer was lost: $result", result.contains("42"))
    }

    @Test fun `strips think tag block (DeepSeek style)`() {
        val raw = "<think>Considering the options...</think>Take the second exit."
        val result = ResponseFormatter.format(raw)
        assertFalse(result.contains("Considering"))
        assertTrue(result.contains("Take the second exit"))
    }

    @Test fun `strips reasoning tag family`() {
        val raw = "<reasoning>Step 1: parse. Step 2: compute.</reasoning>It's sunny."
        val result = ResponseFormatter.format(raw)
        assertFalse(result.contains("Step 1"))
        assertTrue(result.contains("sunny"))
    }

    @Test fun `strips leading bold thinking preamble`() {
        val raw = "**Thinking:** I need to recall the user's location.\n\nYou're in Wrexham."
        val result = ResponseFormatter.format(raw)
        assertFalse("preamble leaked: $result", result.contains("recall", ignoreCase = true))
        assertTrue("answer missing: $result", result.contains("Wrexham"))
    }

    @Test fun `strips leading Thought- preamble`() {
        val raw = "Thought: Let me work through this.\n\nThe meeting is at 3 pm."
        val result = ResponseFormatter.format(raw)
        assertFalse(result.contains("work through", ignoreCase = true))
        assertTrue(result.contains("3 pm"))
    }

    @Test fun `does not strip mid-answer mention of thinking`() {
        // Anchored regex must not eat legitimate prose.
        val raw = "I was thinking about the trip to Paris."
        val result = ResponseFormatter.format(raw)
        assertTrue(result.contains("Paris"))
        assertTrue(result.contains("thinking"))
    }
}
