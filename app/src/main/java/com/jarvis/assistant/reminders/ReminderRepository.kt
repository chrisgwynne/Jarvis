package com.jarvis.assistant.reminders

import android.util.Log
import com.jarvis.assistant.reminders.db.dao.ScheduledItemDao
import com.jarvis.assistant.reminders.db.entity.DeliveryMode
import com.jarvis.assistant.reminders.db.entity.ScheduledItem
import com.jarvis.assistant.reminders.db.entity.ScheduledItemStatus
import com.jarvis.assistant.reminders.db.entity.ScheduledItemType

/**
 * ReminderRepository — single source of truth for creating, cancelling,
 * querying, and rescheduling [ScheduledItem] entries.
 *
 * Combines [ScheduledItemDao] (persistence) with [ReminderScheduler]
 * (AlarmManager) so callers never have to touch both directly.
 */
class ReminderRepository(
    private val dao: ScheduledItemDao,
    private val scheduler: ReminderScheduler
) {

    companion object {
        private const val TAG = "ReminderRepository"
        private const val DELIVERED_PRUNE_AGE_MS = 7 * 24 * 3_600_000L // 7 days
    }

    /** Create a new reminder/timer and immediately schedule its alarm. */
    suspend fun create(
        label: String,
        triggerAtMs: Long,
        type: ScheduledItemType,
        deliveryMode: DeliveryMode = DeliveryMode.SPEAK_IF_IDLE
    ): ScheduledItem {
        val item = ScheduledItem(
            label        = label,
            triggerAtMs  = triggerAtMs,
            type         = type,
            deliveryMode = deliveryMode
        )
        val id = dao.insert(item)
        val inserted = item.copy(id = id)
        scheduler.schedule(id, triggerAtMs)
        Log.d(TAG, "Created ${type.name} id=$id '${label}' at=$triggerAtMs")
        return inserted
    }

    /** Cancel a pending item — updates DB and cancels the AlarmManager entry. */
    suspend fun cancel(id: Long) {
        dao.updateStatus(id, ScheduledItemStatus.CANCELLED)
        scheduler.cancel(id)
        Log.d(TAG, "Cancelled item id=$id")
    }

    /** Mark an item as delivered (called after it has been spoken/notified). */
    suspend fun markDelivered(id: Long) {
        dao.updateStatus(id, ScheduledItemStatus.DELIVERED)
        Log.d(TAG, "Marked delivered id=$id")
    }

    suspend fun getById(id: Long): ScheduledItem? = dao.getById(id)

    /** All items still waiting to fire. */
    suspend fun getPending(): List<ScheduledItem> = dao.getPending()

    /**
     * Reschedule all PENDING items.
     * Called by [BootReceiver] because AlarmManager entries are lost when the
     * device powers off.  Items whose trigger time has already passed are
     * rescheduled 1 second in the future so they fire immediately on boot.
     */
    suspend fun rescheduleAll() {
        val pending = dao.getPending()
        val now     = System.currentTimeMillis()
        for (item in pending) {
            val fireAt = if (item.triggerAtMs <= now) now + 1_000L else item.triggerAtMs
            scheduler.schedule(item.id, fireAt)
        }
        Log.d(TAG, "Rescheduled ${pending.size} pending item(s) after boot")
    }

    /** Remove delivered records older than 7 days to keep the table small. */
    suspend fun pruneDelivered() {
        dao.pruneDelivered(System.currentTimeMillis() - DELIVERED_PRUNE_AGE_MS)
    }
}
