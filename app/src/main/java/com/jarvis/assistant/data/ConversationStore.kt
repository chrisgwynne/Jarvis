package com.jarvis.assistant.data

import android.content.Context
import com.jarvis.assistant.llm.Message
import com.jarvis.assistant.prompt.DefaultSystemPrompt

/**
 * ConversationStore — in-memory conversation history with automatic system prompt injection.
 *
 * The system prompt is rebuilt on every call so it always reflects the current
 * time, battery level, and device model.  It is never stored in history.
 */
class ConversationStore(private val context: Context) : CompressibleStore {

    companion object {
        const val MAX_HISTORY_PAIRS = 6

        // Cap the rolling summary so it can't grow unbounded as new
        // pairs are repeatedly compressed and appended across a long session.
        private const val ROLLING_CONTEXT_MAX_CHARS = 4_000

        /**
         * Approximate token cap for the live history window.  Token counting
         * isn't exact without a tokenizer but chars / 4 is a common English
         * estimate for OpenAI-family tokenisation and keeps the window sane
         * when one turn (e.g. a tool result dump) is unusually long.
         */
        private const val MAX_HISTORY_TOKENS_APPROX = 2_000
        private const val CHARS_PER_TOKEN_APPROX    = 4

        /** Delegates to [DefaultSystemPrompt.build] — retained for backwards compat. */
        fun buildSystemPrompt(context: Context): String = DefaultSystemPrompt.build(context)
    }

    private val history = ArrayDeque<Message>()
    private val lock = Any()

    /**
     * Rolling summary of turn-pairs that have been compressed out of [history].
     * Injected as a pinned context block before the live history on every call.
     * Null until the first compression occurs.
     */
    @Volatile var rollingContext: String? = null
        private set

    fun addMessage(role: String, content: String) = synchronized(lock) {
        history.addLast(Message(role = role, content = content))
        enforceWindow()
    }

    /**
     * Keep the live history inside both a turn-pair cap and an approximate
     * token cap.  A single oversized turn (tool result, long web summary)
     * used to push only one message out at a time; honour the byte budget
     * too so context bloat is bounded even with small message counts.
     */
    private fun enforceWindow() {
        val maxMessages = MAX_HISTORY_PAIRS * 2
        while (history.size > maxMessages) history.removeFirst()
        // Char-based approximation of token budget.  Drop from the front
        // until the remaining window fits; always keep at least the last
        // pair so the immediate turn isn't evicted.
        val tokenBudgetChars = MAX_HISTORY_TOKENS_APPROX * CHARS_PER_TOKEN_APPROX
        var totalChars = history.sumOf { it.content.length }
        while (totalChars > tokenBudgetChars && history.size > 2) {
            totalChars -= history.removeFirst().content.length
        }
    }

    fun getContextMessages(): List<Message> = synchronized(lock) {
        buildList {
            add(Message(role = "system", content = buildSystemPrompt(context)))
            rollingContext?.let {
                add(Message(role = "system", content = "Earlier in this conversation: $it"))
            }
            addAll(history)
        }
    }

    /**
     * Return the oldest [pairs] turn-pairs as a flat list for summarisation.
     * Returns an empty list if fewer pairs are available.
     */
    override fun oldestPairs(pairs: Int): List<Message> = synchronized(lock) {
        history.take(pairs * 2).toList()
    }

    /**
     * Remove the oldest [pairs] turn-pairs from live history and store [summary]
     * as the new rolling context. Called by [ConversationCompressor] on Dispatchers.IO.
     */
    override fun applyRollingContext(summary: String, pairs: Int) = synchronized(lock) {
        repeat(pairs * 2) { if (history.isNotEmpty()) history.removeFirst() }
        val combined = rollingContext?.let { "$it\n$summary" } ?: summary
        // Trim from the start so the most recent summary always survives.
        rollingContext = if (combined.length > ROLLING_CONTEXT_MAX_CHARS) {
            combined.takeLast(ROLLING_CONTEXT_MAX_CHARS)
        } else {
            combined
        }
    }

    /** Return the last [n] user/assistant messages (no system prompt). */
    fun getRecentMessages(n: Int): List<Message> = synchronized(lock) {
        history.takeLast(n).toList()
    }

    /**
     * Replace the most recent assistant message with [content].  No-op if the
     * last message isn't from the assistant or history is empty.  Used after
     * an interrupted response to rewrite what the LLM thinks it said so the
     * next turn doesn't see the unspoken tail as part of history.
     */
    fun replaceLastAssistant(content: String) = synchronized(lock) {
        val last = history.lastOrNull() ?: return@synchronized
        if (last.role != "assistant") return@synchronized
        history.removeLast()
        history.addLast(Message(role = "assistant", content = content))
    }

    /**
     * Drop the most recent message if it's from the assistant.  Used at the
     * start of an interrupted-response resume so the LLM can stream a fresh
     * continuation without a stale assistant turn sitting before it.
     */
    fun dropLastAssistant() = synchronized(lock) {
        val last = history.lastOrNull() ?: return@synchronized
        if (last.role != "assistant") return@synchronized
        history.removeLast()
    }

    val isEmpty: Boolean get() = synchronized(lock) { history.isEmpty() }
    fun clear() = synchronized(lock) { history.clear(); rollingContext = null }
    override val size: Int get() = synchronized(lock) { history.size }
}
