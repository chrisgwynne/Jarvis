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

        private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy")
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a")

        fun buildSystemPrompt(context: Context): String {
            val today   = LocalDate.now().format(DATE_FORMAT)
            val time    = LocalTime.now().format(TIME_FORMAT)
            val bm      = context.getSystemService(BatteryManager::class.java)
            val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val battStr = if (battery >= 0) "$battery%" else "unknown"
            val device  = Build.MODEL

            return "You are Jarvis, a concise voice assistant running on Android. " +
                   "Today is $today. The current time is $time. " +
                   "Battery level: $battStr. Device: $device. " +
                   "Keep all responses under 2 sentences for voice output. " +
                   "Do not use markdown formatting. " +
                   "Respond conversationally and naturally. " +
                   "You always know the current time and date — state them confidently when asked. " +
                   "Never mention knowledge cutoffs, training data, or lack of real-time access — " +
                   "just answer based on what you know."
        }
    }

    private val history = ArrayDeque<Message>()

    /**
     * Rolling summary of turn-pairs that have been compressed out of [history].
     * Injected as a pinned context block before the live history on every call.
     * Null until the first compression occurs.
     */
    @Volatile var rollingContext: String? = null
        private set

    fun addMessage(role: String, content: String) {
        history.addLast(Message(role = role, content = content))
        val maxMessages = MAX_HISTORY_PAIRS * 2
        while (history.size > maxMessages) history.removeFirst()
    }

    fun getContextMessages(): List<Message> = buildList {
        add(Message(role = "system", content = buildSystemPrompt(context)))
        rollingContext?.let {
            add(Message(role = "system", content = "Earlier in this conversation: $it"))
        }
        addAll(history)
    }

    /**
     * Return the oldest [pairs] turn-pairs as a flat list for summarisation.
     * Returns an empty list if fewer pairs are available.
     */
    override fun oldestPairs(pairs: Int): List<Message> = history.take(pairs * 2).toList()

    /**
     * Remove the oldest [pairs] turn-pairs from live history and store [summary]
     * as the new rolling context. Called by [ConversationCompressor] on Dispatchers.IO.
     */
    override fun applyRollingContext(summary: String, pairs: Int) {
        repeat(pairs * 2) { if (history.isNotEmpty()) history.removeFirst() }
        rollingContext = if (rollingContext != null) {
            "$rollingContext\n$summary"
        } else {
            summary
        }
    }

    /** Return the last [n] user/assistant messages (no system prompt). */
    fun getRecentMessages(n: Int): List<Message> = history.takeLast(n).toList()

    val isEmpty: Boolean get() = history.isEmpty()
    fun clear() { history.clear(); rollingContext = null }
    override val size: Int get() = history.size
}
