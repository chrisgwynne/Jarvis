package com.jarvis.assistant.core.routines

import com.jarvis.assistant.tools.framework.ToolInput
import java.util.ArrayDeque

/**
 * RecentToolCallBuffer — in-memory ring of the last N successful tool
 * executions, ordered oldest-first. Consumed by the save-routine tool:
 * "save that as a routine called morning coffee" takes the last M entries
 * (default 4) and persists them as a [SavedRoutineEntity].
 *
 * Kept in-process (no DB). A routine the user means to save has to be
 * saved in the same session; we don't try to guess across app restarts.
 */
class RecentToolCallBuffer(
    private val capacity: Int = 8,
    private val ttlMs: Long = 10 * 60 * 1000L,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    data class Entry(
        val toolName: String,
        val shortLabel: String,
        val reversible: Boolean,
        val params: Map<String, String>,
        val transcript: String,
        val tsMs: Long,
    )

    private val lock = Any()
    private val buffer = ArrayDeque<Entry>()

    fun record(toolName: String, shortLabel: String, reversible: Boolean, input: ToolInput) {
        synchronized(lock) {
            evictStale()
            buffer.addLast(
                Entry(
                    toolName = toolName,
                    shortLabel = shortLabel,
                    reversible = reversible,
                    params = input.params,
                    transcript = input.transcript,
                    tsMs = nowMs(),
                )
            )
            while (buffer.size > capacity) buffer.removeFirst()
        }
    }

    fun lastN(n: Int): List<Entry> = synchronized(lock) {
        evictStale()
        if (buffer.isEmpty()) return emptyList()
        val take = n.coerceAtMost(buffer.size)
        buffer.takeLast(take)
    }

    fun clear() = synchronized(lock) { buffer.clear() }

    private fun evictStale() {
        val cutoff = nowMs() - ttlMs
        while (buffer.isNotEmpty() && buffer.peekFirst().tsMs < cutoff) buffer.removeFirst()
    }
}
