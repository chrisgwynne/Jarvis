package com.jarvis.assistant.llm.providers

import org.junit.Assert.assertEquals
import org.junit.Test

class MiniMaxProviderTest {

    @Test
    fun `canonical base model passes through`() {
        assertEquals("MiniMax-M2.7", MiniMaxProvider.canonicalise("MiniMax-M2.7"))
    }

    @Test
    fun `case and separators don't matter`() {
        assertEquals("MiniMax-M2.7",       MiniMaxProvider.canonicalise("minimax_m2.7"))
        assertEquals("MiniMax-M2.7",       MiniMaxProvider.canonicalise("MINIMAX M2.7"))
        assertEquals("MiniMax-M2.7-highspeed", MiniMaxProvider.canonicalise("minimax m2.7 high-speed"))
    }

    @Test
    fun `highspeed variants all map to the canonical Speed id`() {
        listOf(
            "MiniMax-M2.7-highspeed",
            "Minimax-M2.7-fast",
            "minimax m2.7 lightning",
            "MiniMax_M2.7_Speed",
        ).forEach {
            assertEquals("'$it' should canonicalise to Speed",
                "MiniMax-M2.7-highspeed", MiniMaxProvider.canonicalise(it))
        }
    }

    @Test
    fun `M dot 27 STT mishearing maps to M2 dot 7`() {
        assertEquals("MiniMax-M2.7", MiniMaxProvider.canonicalise("Minimax-M.27"))
    }

    @Test
    fun `older variants map`() {
        assertEquals("MiniMax-Text-01", MiniMaxProvider.canonicalise("minimax text-01"))
        assertEquals("abab6.5s-chat",   MiniMaxProvider.canonicalise("ABAB6.5s"))
    }

    @Test
    fun `unknown ids pass through trimmed`() {
        // Future variants we haven't catalogued shouldn't be silently
        // remapped to the default — pass them straight through.
        assertEquals("MiniMax-M3.0", MiniMaxProvider.canonicalise("  MiniMax-M3.0  "))
    }

    @Test
    fun `blank defaults to base model`() {
        assertEquals("MiniMax-M2.7", MiniMaxProvider.canonicalise("  "))
    }
}
