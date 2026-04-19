package com.jarvis.assistant.runtime.reference

import java.util.ArrayDeque
import java.util.UUID

/**
 * LastActionStore — in-memory ring buffer of recent [LastAction] entries.
 *
 * Capped at [capacity] entries and a soft TTL of [ttlMs] so very old entries
 * don't resolve ambiguous references like "do the same".  Thread-safe via a
 * single intrinsic lock — writes and reads are cheap (≤8 entries).
 *
 * Entries with reversible=false are still recorded so "what did you just do?"
 * questions work; [mostRecentReversible] filters for undo flows.
 */
class LastActionStore(
    private val capacity: Int = 8,
    private val ttlMs: Long = 10 * 60 * 1000L,
    private val nowMsProvider: () -> Long = System::currentTimeMillis
) {

    private val buffer = ArrayDeque<LastAction>()
    private val lock = Any()

    /** Record a new tool-call action and return the assigned id. */
    fun recordToolCall(
        toolName: String,
        argsJson: String,
        originatingTranscript: String,
        shortLabel: String,
        reversible: Boolean,
        rawData: String = ""
    ): LastAction.ToolCall {
        val entry = LastAction.ToolCall(
            id                    = UUID.randomUUID().toString(),
            createdAtMs           = nowMsProvider(),
            originatingTranscript = originatingTranscript,
            shortLabel            = shortLabel,
            reversible            = reversible,
            toolName              = toolName,
            argsJson              = argsJson,
            rawData               = rawData
        )
        push(entry)
        return entry
    }

    fun recordPlanRun(
        planId: String,
        originatingTranscript: String,
        shortLabel: String,
        reversible: Boolean
    ): LastAction.PlanRun {
        val entry = LastAction.PlanRun(
            id                    = UUID.randomUUID().toString(),
            createdAtMs           = nowMsProvider(),
            originatingTranscript = originatingTranscript,
            shortLabel            = shortLabel,
            reversible            = reversible,
            planId                = planId
        )
        push(entry)
        return entry
    }

    private fun push(entry: LastAction) = synchronized(lock) {
        buffer.addFirst(entry)
        while (buffer.size > capacity) buffer.removeLast()
    }

    /** All live (non-expired) entries, newest first. */
    fun snapshot(): List<LastAction> = synchronized(lock) {
        val cutoff = nowMsProvider() - ttlMs
        buffer.filter { it.createdAtMs >= cutoff }
    }

    /** The newest live entry, or null when the buffer is empty / all stale. */
    fun mostRecent(): LastAction? = snapshot().firstOrNull()

    /** The newest live entry that can be reversed, or null. */
    fun mostRecentReversible(): LastAction? =
        snapshot().firstOrNull { it.reversible }

    /** Clear the buffer (used by tests and on "forget" voice commands). */
    fun clear() = synchronized(lock) { buffer.clear() }

    /**
     * Render a short prompt fragment summarising the last few actions so the
     * LLM can resolve "that" / "the same" referentially.
     *
     * Returns null when the buffer is empty — callers should skip the section.
     */
    fun toPromptFragment(max: Int = 3): String? {
        val live = snapshot()
        if (live.isEmpty()) return null
        val lines = live.take(max).joinToString("\n") { "- ${it.shortLabel}" }
        return "Recent actions:\n$lines"
    }
}
