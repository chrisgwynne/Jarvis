package com.jarvis.assistant.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * ReminderScheduler — wraps Android [AlarmManager] to schedule and cancel
 * alarms for [ScheduledItem] entries.
 *
 * Uses [AlarmManager.setExactAndAllowWhileIdle] so the alarm fires even in
 * Doze mode. Falls back to [AlarmManager.set] (inexact) on API 31+ when the
 * user has not granted SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM.
 */
class ReminderScheduler(private val context: Context) {

    companion object {
        private const val TAG = "ReminderScheduler"
    }

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun schedule(id: Long, triggerAtMs: Long) {
        val intent = buildIntent(id)
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                           alarmManager.canScheduleExactAlarms()
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAtMs, intent
                )
                Log.d(TAG, "Exact alarm scheduled id=$id at $triggerAtMs")
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMs, intent)
                Log.w(TAG, "Exact alarm permission not granted — using inexact for id=$id")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException scheduling alarm id=$id: ${e.message}")
            // Last resort — schedule inexact
            try { alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMs, intent) }
            catch (ignored: Exception) { Log.e(TAG, "Failed to schedule even inexact alarm", ignored) }
        }
    }

    fun cancel(id: Long) {
        try {
            alarmManager.cancel(buildIntent(id))
            Log.d(TAG, "Alarm cancelled id=$id")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel alarm id=$id: ${e.message}")
        }
    }

    private fun buildIntent(id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            (id and 0x7FFF_FFFFL).toInt(), // safe truncation — keeps lower 31 bits positive
            Intent(context, ReminderAlarmReceiver::class.java).apply {
                putExtra(ReminderAlarmReceiver.EXTRA_ITEM_ID, id)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}
