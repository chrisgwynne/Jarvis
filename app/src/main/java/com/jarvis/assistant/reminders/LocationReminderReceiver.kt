package com.jarvis.assistant.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.jarvis.assistant.service.JarvisService
import com.jarvis.assistant.util.JarvisNotificationHelper

/**
 * LocationReminderReceiver — receives geofence transition broadcasts from
 * Google Play Services and forwards them to [JarvisService] for spoken delivery.
 *
 * Registered in AndroidManifest.xml as a [BroadcastReceiver].
 *
 * FLOW:
 *   GeofencingClient → (PendingIntent) → LocationReminderReceiver
 *     → reads reminder label from [LocationReminderManager]
 *     → sends ACTION_LOCATION_REMINDER broadcast to [JarvisService]
 *     → posts a fallback notification via [JarvisNotificationHelper]
 *
 * REQUIRED PERMISSIONS (already declared for geofencing):
 *   android.permission.ACCESS_FINE_LOCATION
 *   android.permission.ACCESS_BACKGROUND_LOCATION  (Android 10+)
 */
class LocationReminderReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "LocationReminderReceiver"

        /** Action sent by GeofencingClient — also used as this receiver's intent filter. */
        const val ACTION_GEOFENCE_TRANSITION = "com.jarvis.assistant.GEOFENCE_TRANSITION"

        /** Action broadcast to JarvisService when a location reminder fires. */
        const val ACTION_LOCATION_REMINDER   = "com.jarvis.assistant.LOCATION_REMINDER"

        /** Intent extra: the human-readable reminder label to speak. */
        const val EXTRA_REMINDER_LABEL       = "reminder_label"

        /** Intent extra: the reminder ID (for deduplication / cancellation). */
        const val EXTRA_REMINDER_ID          = "location_reminder_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: action=${intent.action}")

        @Suppress("DEPRECATION")
        val event = GeofencingEvent.fromIntent(intent) ?: run {
            Log.w(TAG, "GeofencingEvent.fromIntent returned null — ignoring")
            return
        }

        if (event.hasError()) {
            Log.w(TAG, "GeofencingEvent has error code: ${event.errorCode}")
            return
        }

        // We only registered GEOFENCE_TRANSITION_ENTER, but guard anyway
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) {
            Log.d(TAG, "Non-enter transition (${event.geofenceTransition}) — ignoring")
            return
        }

        val triggeringGeofences = event.triggeringGeofences
        if (triggeringGeofences.isNullOrEmpty()) {
            Log.w(TAG, "No triggering geofences in event — ignoring")
            return
        }

        // Look up reminder labels from persistent storage
        val manager = LocationReminderManager(context)

        triggeringGeofences.forEach { geofence ->
            val reminderId = geofence.requestId
            val reminder   = manager.findById(reminderId)

            if (reminder == null) {
                Log.w(TAG, "No stored reminder for geofence id=$reminderId — skipping")
                return@forEach
            }

            Log.i(TAG, "Location reminder triggered: id=$reminderId label='${reminder.label}'")

            // 1. Forward to JarvisService for spoken delivery
            deliverToService(context, reminder)

            // 2. Post a visual notification as a fallback (in case service is not running)
            postNotification(context, reminder)
        }
    }

    // ── Delivery helpers ──────────────────────────────────────────────────────

    /**
     * Sends a broadcast to [JarvisService] so it can speak the reminder aloud.
     */
    private fun deliverToService(context: Context, reminder: LocationReminder) {
        val serviceIntent = Intent(context, JarvisService::class.java).apply {
            action = ACTION_LOCATION_REMINDER
            putExtra(EXTRA_REMINDER_LABEL, reminder.label)
            putExtra(EXTRA_REMINDER_ID,    reminder.id)
        }
        try {
            context.startForegroundService(serviceIntent)
            Log.d(TAG, "Dispatched LOCATION_REMINDER to JarvisService for: ${reminder.label}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start JarvisService for location reminder: ${e.message}", e)
        }
    }

    /**
     * Posts a high-priority reminder notification as a fallback.
     */
    private fun postNotification(context: Context, reminder: LocationReminder) {
        try {
            JarvisNotificationHelper.postReminder(
                context = context,
                title   = "Location Reminder",
                body    = reminder.label
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post location reminder notification: ${e.message}")
        }
    }
}
