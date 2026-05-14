package com.jarvis.assistant.proactive.scheduled

/**
 * Abstraction over an upstream "give me upcoming items in the next N ms"
 * provider.  Each concrete implementation (Calendar / Todoist / local
 * reminders) is owned by [ScheduledReminderEngine] and consulted on
 * every refresh tick.
 *
 * Implementations MUST be cheap on the caller's coroutine — the engine
 * times out individual calls so a slow Todoist round-trip can't stall
 * Calendar refresh.  IO should be hopped to Dispatchers.IO internally.
 *
 * Implementations MUST NOT throw — return an empty list and log the
 * reason.  The engine treats a thrown exception as a refresh failure
 * for that source.
 */
interface ScheduledReminderItemSource {

    val source: ScheduledReminderSource

    /**
     * Items that start in the window `[nowMs, nowMs + lookAheadMs)`.
     * The engine deduplicates / re-schedules from these.
     */
    suspend fun fetchUpcoming(nowMs: Long, lookAheadMs: Long): List<ScheduledReminderItem>
}
