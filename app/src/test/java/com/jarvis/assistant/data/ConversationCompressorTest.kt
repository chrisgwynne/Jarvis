package com.jarvis.assistant.data

import com.jarvis.assistant.llm.Message
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCompressorTest {

    // ── Minimal in-memory CompressibleStore — no Android Context needed ───────

    private class FakeStore : CompressibleStore {
        private val history = ArrayDeque<Message>()
        var rollingContext: String? = null

        override val size: Int get() = history.size

        fun addPair(userContent: String, assistantContent: String) {
            history.addLast(Message("user", userContent))
            history.addLast(Message("assistant", assistantContent))
        }

        override fun oldestPairs(pairs: Int): List<Message> =
            history.take(pairs * 2).toList()

        override fun applyRollingContext(summary: String, pairs: Int) {
            repeat(pairs * 2) { if (history.isNotEmpty()) history.removeFirst() }
            rollingContext = if (rollingContext != null) "$rollingContext\n$summary" else summary
        }
    }

    private val summariserCalls = mutableListOf<List<Message>>()
    private var summariserResponse = "Summary of earlier turns."

    private suspend fun fakeSummarise(messages: List<Message>): String {
        summariserCalls.add(messages)
        return summariserResponse
    }

    private fun reset() {
        summariserCalls.clear()
        summariserResponse = "Summary of earlier turns."
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `no compression below threshold`() = runTest {
        reset()
        val store      = FakeStore()
        val compressor = ConversationCompressor(store, ::fakeSummarise)

        repeat(2) { i -> store.addPair("user $i", "assistant $i") }

        compressor.maybeCompress()

        assertEquals("Summariser should not be called", 0, summariserCalls.size)
        assertNull("Rolling context should be null", store.rollingContext)
        assertEquals(4, store.size)
    }

    @Test
    fun `compresses oldest pairs when threshold is met`() = runTest {
        reset()
        val store      = FakeStore()
        val compressor = ConversationCompressor(store, ::fakeSummarise)
        val threshold  = ConversationStore.MAX_HISTORY_PAIRS - 1

        repeat(threshold) { i -> store.addPair("user $i", "assistant $i") }
        val initialSize = store.size   // threshold * 2

        compressor.maybeCompress()

        assertEquals("Summariser called once", 1, summariserCalls.size)
        assertNotNull("Rolling context set", store.rollingContext)
        assertEquals(summariserResponse, store.rollingContext)

        // Oldest 2 pairs (4 messages) removed
        assertEquals(initialSize - 4, store.size)
    }

    @Test
    fun `summary prompt contains system instruction and the oldest pairs`() = runTest {
        reset()
        val store      = FakeStore()
        val compressor = ConversationCompressor(store, ::fakeSummarise)
        val threshold  = ConversationStore.MAX_HISTORY_PAIRS - 1

        repeat(threshold) { i -> store.addPair("turn $i user", "turn $i assistant") }

        compressor.maybeCompress()

        val prompt = summariserCalls.first()
        // First message must be the system instruction
        assertEquals("system", prompt[0].role)
        assertTrue(prompt[0].content.contains("Summarise"))

        // The following messages are the oldest 2 pairs from the store
        assertEquals("user",      prompt[1].role)
        assertEquals("turn 0 user", prompt[1].content)
        assertEquals("assistant", prompt[2].role)
        assertEquals("turn 1 user", prompt[3].content)
    }

    @Test
    fun `second compression appends to existing rolling context`() = runTest {
        reset()
        val store      = FakeStore()
        val compressor = ConversationCompressor(store, ::fakeSummarise)
        val threshold  = ConversationStore.MAX_HISTORY_PAIRS - 1

        // First round
        repeat(threshold) { i -> store.addPair("a$i", "b$i") }
        summariserResponse = "First summary."
        compressor.maybeCompress()

        // Refill to trigger a second compression
        repeat(2) { i -> store.addPair("c$i", "d$i") }
        summariserResponse = "Second summary."
        compressor.maybeCompress()

        val ctx = store.rollingContext ?: error("Rolling context must be set")
        assertTrue("Contains first summary",  ctx.contains("First summary."))
        assertTrue("Contains second summary", ctx.contains("Second summary."))
    }

    @Test
    fun `blank summariser response does not modify store`() = runTest {
        reset()
        val store      = FakeStore()
        val compressor = ConversationCompressor(store, { _: List<Message> -> "   " })
        val threshold  = ConversationStore.MAX_HISTORY_PAIRS - 1

        repeat(threshold) { i -> store.addPair("x$i", "y$i") }
        val sizeBefore = store.size

        compressor.maybeCompress()

        assertNull("Rolling context must be null on blank summary", store.rollingContext)
        assertEquals("History must not shrink on blank summary", sizeBefore, store.size)
    }
}
