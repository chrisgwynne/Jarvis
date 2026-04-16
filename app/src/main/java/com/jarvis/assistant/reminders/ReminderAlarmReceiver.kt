package com.jarvis.assistant.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.service.JarvisService

/**
 * ReminderAlarmReceiver — receives AlarmManager broadcasts when a
 * [ScheduledItem] is due and forwards them to [JarvisService] for delivery.
 *
 * Must be registered in AndroidManifest.xml.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_ITEM_ID = "reminder_item_id"
        private const val TAG   = "ReminderAlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val itemId = intent.getLongExtra(EXTRA_ITEM_ID, -1L)
        if (itemId < 0L) {
            Log.w(TAG, "Received alarm broadcast with no item id — ignoring")
            return
        }
        Log.d(TAG, "Alarm fired for reminder id=$itemId")

        // Start (or wake) JarvisService to deliver the reminder aloud
        val serviceIntent = Intent(context, JarvisService::class.java).apply {
            action = JarvisService.ACTION_REMINDER_TRIGGER
            putExtra(JarvisService.EXTRA_REMINDER_ID, itemId)
        }
        context.startForegroundService(serviceIntent)
    }
}
