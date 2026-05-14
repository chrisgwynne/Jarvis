package com.jarvis.assistant.todoist.edit

import java.util.concurrent.atomic.AtomicReference

/**
 * RecentTaskContextStore — single-slot, short-TTL pointer to the task
 * the user most recently created / listed / modified.  "That" /
 * "actually 9pm" / "delete that" all resolve against this pointer.
 *
 * Why single-slot: ambiguity is the bigger risk.  If the user has
 * touched several tasks recently we ASK rather than guess which one
 * "that" means — see the audit spec ("Do not guess wrong task silently").
 *
 * Lifetime: configurable TTL (default 5 minutes).  Setting a new entry
 * replaces any previous one — last-touched wins.  Atomic so reads from
 * the UI / runtime / proactive engine stay consistent.
 */
class RecentTaskContextStore(
    private val ttlMs: Long = 5 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    enum class Source { CREATED, LISTED, MODIFIED }

    data class Entry(
        val taskId: String,
        val content: String,
        val source: Source,
        val touchedAtMs: Long,
    )

    private val slot = AtomicReference<Entry?>(null)

    fun remember(taskId: String, content: String, source: Source) {
        if (taskId.isBlank()) return
        slot.set(Entry(taskId, content.trim(), source, clock()))
    }

    /** Read the active entry, dropping it if it has expired. */
    fun peek(): Entry? {
        val cur = slot.get() ?: return null
        if (clock() - cur.touchedAtMs > ttlMs) {
            slot.compareAndSet(cur, null)
            return null
        }
        return cur
    }

    /** Explicit clear — call after a delete to prevent stale "undo that". */
    fun clear() { slot.set(null) }
}
