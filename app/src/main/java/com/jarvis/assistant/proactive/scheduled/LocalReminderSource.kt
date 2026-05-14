package com.jarvis.assistant.proactive.scheduled

import android.util.Log
import com.jarvis.assistant.reminders.ReminderRepository
import com.jarvis.assistant.reminders.db.entity.ScheduledItemType

/**
 * LocalReminderSource — surface upcoming local reminders (Jarvis-owned
 * Room rows) into the scheduled-reminder engine.
 *
 * This works alongside the existing [com.jarvis.assistant.reminders
 * .ReminderScheduler] which already fires the actual ON-TIME alarm via
 * AlarmManager.  The engine adds the *pre-warning* 30m / 10m lanes.
 * The scheduler keeps responsibility for the 0-minute firing so we
 * don't end up with two systems racing for the same trigger time.
 *
 * Timers are excluded — they're short by nature; a 30-minute warning
 * for a 5-minute timer is nonsense.
 */
class LocalReminderSource(
    private val repository: ReminderRepository,
) : ScheduledReminderItemSource {

    override val source: ScheduledReminderSource = ScheduledReminderSource.LOCAL

    override suspend fun fetchUpcoming(nowMs: Long, lookAheadMs: Long): List<ScheduledReminderItem> {
        val endMs = nowMs + lookAheadMs
        return try {
            repository.getPending()
                .asSequence()
                .filter { it.type == ScheduledItemType.REMINDER }
                .filter { it.triggerAtMs in nowMs..endMs }
                .map {
                    ScheduledReminderItem(
                        source     = ScheduledReminderSource.LOCAL,
                        sourceId   = it.id.toString(),
                        title      = it.label,
                        startMs    = it.triggerAtMs,
                        fingerprint = "${it.triggerAtMs}:${it.label}",
                    )
                }
                .toList()
        } catch (t: Throwable) {
            Log.w(TAG, "Local reminder fetch failed", t)
            emptyList()
        }
    }

    companion object { private const val TAG = "LocalReminderSrc" }
}
