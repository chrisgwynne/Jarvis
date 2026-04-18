package com.jarvis.assistant.data

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import com.jarvis.assistant.llm.Message
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

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

        private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy")
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a")

        fun buildSystemPrompt(context: Context): String {
            val today   = LocalDate.now().format(DATE_FORMAT)
            val time    = LocalTime.now().format(TIME_FORMAT)
            val bm      = context.getSystemService(BatteryManager::class.java)
            val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val battStr = if (battery >= 0) "$battery%" else "unknown"
            val device  = Build.MODEL

            return "You are Jarvis. Talk like a person in the conversation, not a generic assistant. " +
                   "Today is $today. The current time is $time. " +
                   "Battery level: $battStr. Device: $device. " +
                   "Default reply: 1 short sentence. Less output is more natural. " +
                   "Small talk gets brief reactions — 'Long day' → 'Yeah, sounds it.', 'Nice' → 'Yeah.', 'Ok' → no extra. " +
                   "Only expand if the user asks for detail or the task requires it. Never add suggestions or follow-ups by default. " +
                   "Never use phrases like 'I can help with that', 'Here's what I found', " +
                   "'Would you like me to…', or 'Let me know if you need anything else'. " +
                   "Do not narrate actions. Do not over-explain. Do not echo the question back. " +
                   "Confirm actions in the fewest words possible — 'Opening Spotify.', 'Timer set.', 'Done.'. " +
                   "No markdown. Casual, direct, confident — not over-friendly, not robotic. " +
                   "State time and date confidently. Never mention knowledge cutoffs or real-time access."
        }
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
        val maxMessages = MAX_HISTORY_PAIRS * 2
        while (history.size > maxMessages) history.removeFirst()
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
