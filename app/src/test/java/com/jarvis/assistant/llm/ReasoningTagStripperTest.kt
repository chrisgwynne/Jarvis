package com.jarvis.assistant.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReasoningTagStripperTest — pins the streaming behaviour the TTS path
 * relies on: chain-of-thought tag families never reach the speaker, even
 * when the opener / closer arrive split across tokens, while normal text
 * passes through unchanged.
 */
class ReasoningTagStripperTest {

    private fun stream(stripper: ReasoningTagStripper, vararg tokens: String): String {
        val out = StringBuilder()
        for (t in tokens) out.append(stripper.process(t))
        out.append(stripper.flush())
        return out.toString()
    }

    @Test fun `passes plain text through unchanged`() {
        val s = ReasoningTagStripper()
        assertEquals("Hello world.", stream(s, "Hello ", "world."))
    }

    @Test fun `drops a complete thinking block`() {
        val s = ReasoningTagStripper()
        val out = stream(s, "<thinking>internal monologue</thinking>The answer is 42.")
        assertEquals("The answer is 42.", out)
    }

    @Test fun `drops a complete think block (DeepSeek)`() {
        val s = ReasoningTagStripper()
        val out = stream(s, "<think>step 1\nstep 2</think>Take the second exit.")
        assertEquals("Take the second exit.", out)
    }

    @Test fun `drops new tag families — reasoning reflection scratchpad analysis plan`() {
        for (tag in listOf("reasoning", "reflection", "scratchpad", "analysis", "plan")) {
            val s = ReasoningTagStripper()
            val out = stream(s, "<$tag>private</$tag>public")
            assertEquals("Tag <$tag> not stripped: $out", "public", out)
        }
    }

    @Test fun `tag split across tokens is still dropped`() {
        val s = ReasoningTagStripper()
        // Mimic an SSE stream that fragments the opener and closer.
        val out = stream(
            s,
            "<th", "ink", ">",
            "noise ",
            "</th", "ink>",
            "Real answer."
        )
        assertEquals("Real answer.", out)
    }

    @Test fun `bare lt that is not a tag is preserved`() {
        val s = ReasoningTagStripper()
        val out = stream(s, "x < y is true.")
        assertTrue("Lost the '<' character: $out", out.contains("<"))
        assertTrue(out.contains("true"))
    }

    @Test fun `case insensitive opener`() {
        val s = ReasoningTagStripper()
        val out = stream(s, "<THINKING>noise</thinking>Visible.")
        assertEquals("Visible.", out)
    }

    @Test fun `unclosed reasoning at stream end is dropped`() {
        // Half-open <think> block with no closer must not leak chain-of-thought.
        val s = ReasoningTagStripper()
        val out = stream(s, "<think>partial reasoning that never finishes")
        assertFalse("Unclosed reasoning leaked: $out", out.contains("partial"))
    }

    @Test fun `flush emits a half-formed opener that turned out not to be a tag`() {
        // We buffered "<th" but the stream ended — must emit it, not lose it.
        val s = ReasoningTagStripper()
        val out = stream(s, "before <th")
        assertTrue("Lost the '<th' tail: $out", out.endsWith("<th"))
    }
}
