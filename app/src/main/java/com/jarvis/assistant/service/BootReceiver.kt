package com.jarvis.assistant.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.memory.db.JarvisDatabase
import com.jarvis.assistant.reminders.ReminderRepository
import com.jarvis.assistant.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BootReceiver — auto-starts JarvisService and reschedules pending reminders
 * after device reboot.
 *
 * AlarmManager entries do NOT survive a device reboot, so any PENDING
 * [ScheduledItem]s must be rescheduled here before the service wakes up.
 *
 * REQUIREMENTS:
 *  - RECEIVE_BOOT_COMPLETED permission in the manifest (already declared).
 *  - android:exported="true" on the receiver.
 *  - The user must have opened the app at least once (Android "stopped-state"
 *    protection blocks receivers for never-launched apps).
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.d(TAG, "Boot completed — rescheduling reminders")

            // Reschedule alarms regardless of mic permission — they don't need it.
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = JarvisDatabase.getInstance(context)
                    val repo = ReminderRepository(
                        dao       = db.scheduledItemDao(),
                        scheduler = ReminderScheduler(context)
                    )
                    repo.rescheduleAll()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to reschedule reminders on boot: ${e.message}", e)
                } finally {
                    pendingResult.finish()
                }
            }

            // Android 14 (API 34): starting a microphone-type foreground service requires
            // RECORD_AUDIO to be runtime-granted. If the user hasn't granted it yet (first
            // install, or permission revoked) the OS throws SecurityException and kills the
            // process. Skip the auto-start; the user will launch Jarvis manually instead.
            val recordAudioGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!recordAudioGranted) {
                Log.w(TAG, "RECORD_AUDIO not granted — skipping auto-start from boot")
                return
            }

            Log.d(TAG, "RECORD_AUDIO granted — auto-starting JarvisService")
            JarvisService.start(context)
        }
    }
}
