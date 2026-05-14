package com.jarvis.assistant.proactive.scheduled

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory store for the live set of [ScheduledReminderInstance]s the
 * engine is tracking.
 *
 * Why in-memory and not Room?  Scheduled reminders are derivable from
 * the upstream sources (Calendar query + Todoist filter + local
 * ScheduledItem table).  On cold start the engine re-runs a refresh
 * and the instance set is rebuilt — there's no value in persisting
 * "haven't fired yet" state across process death because the source
 * is the source of truth.  The store's job is debounce + dedupe
 * *within* a process lifetime, which a ConcurrentHashMap handles
 * cheaply.
 *
 * If we later switch to a persistent strategy (e.g. for WorkManager-
 * driven scheduling that survives reboot), this interface is the
 * substitution point.
 */
class ScheduledReminderInstanceStore {

    private val byKey = ConcurrentHashMap<String, ScheduledReminderInstance>()

    /**
     * Return a snapshot of every tracked instance.  Caller MUST treat
     * this as immutable — it's a defensive copy.
     */
    fun snapshot(): List<ScheduledReminderInstance> = byKey.values.toList()

    /** Look up an instance by its dedupe key, or null. */
    fun get(key: String): ScheduledReminderInstance? = byKey[key]

    /**
     * Insert [instance] iff there isn't already one under the same
     * dedupe key, or if the existing one's fingerprint no longer
     * matches (item time changed).  Returns true when a write
     * happened.
     */
    fun putIfChanged(instance: ScheduledReminderInstance): Boolean {
        val existing = byKey[instance.dedupeKey]
        if (existing != null && existing.fingerprint == instance.fingerprint &&
            existing.scheduledAtMs == instance.scheduledAtMs &&
            !existing.dismissed
        ) return false
        byKey[instance.dedupeKey] = instance
        Log.d(TAG, "[SCHEDULED_REMINDER_INSTANCE_UPSERT] key=${instance.dedupeKey} " +
            "fingerprint=${instance.fingerprint} scheduledAt=${instance.scheduledAtMs}")
        return true
    }

    /** Replace [instance] by dedupe key (e.g. to mark fired/dismissed). */
    fun update(instance: ScheduledReminderInstance) {
        byKey[instance.dedupeKey] = instance
    }

    /** Mark this instance as fired and persist the change. */
    fun markFired(key: String) {
        byKey[key]?.let { byKey[key] = it.copy(fired = true) }
    }

    /** Mark this instance as dismissed (upstream item cancelled). */
    fun markDismissed(key: String) {
        byKey[key]?.let { byKey[key] = it.copy(dismissed = true) }
    }

    /**
     * Remove every instance whose [ScheduledReminderInstance.sourceId] is
     * NOT in [aliveIds] — invoked by the engine after each refresh so
     * cancelled / completed items don't linger and re-fire.  Returns the
     * number of pruned entries.
     */
    fun retainOnly(source: ScheduledReminderSource, aliveIds: Set<String>): Int {
        val toDrop = byKey.values.filter {
            it.source == source && it.sourceId !in aliveIds
        }
        for (gone in toDrop) {
            byKey.remove(gone.dedupeKey)
            Log.d(TAG, "[SCHEDULED_REMINDER_PRUNED] key=${gone.dedupeKey} " +
                "reason=source_item_gone")
        }
        return toDrop.size
    }

    fun clear() { byKey.clear() }

    companion object { private const val TAG = "SchedReminderStore" }
}
