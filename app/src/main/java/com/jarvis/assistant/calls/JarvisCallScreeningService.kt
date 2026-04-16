package com.jarvis.assistant.calls

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.assistant.call.IncomingCallNumberCache

/**
 * JarvisCallScreeningService — screens incoming calls using Android's
 * [CallScreeningService] API (requires Android 10 / API 29+).
 *
 * IMPORTANT — USER ACTION REQUIRED:
 *   This service only receives calls if Jarvis has been selected as the
 *   default call screening app.  Direct the user to:
 *     Phone app → Settings → Caller ID & spam → Caller ID → Jarvis
 *   (exact path varies by OEM/launcher).
 *
 * PERMISSION REQUIRED (in AndroidManifest):
 *   android.permission.BIND_CALL_SCREENING_SERVICE
 *   android.permission.READ_CONTACTS  (for contact lookup)
 *
 * BEHAVIOUR:
 *   • Known contact  → allow immediately (no screening).
 *   • Unknown/private → silence the ringer for 3 s, post a "Screening..." notification,
 *                       then allow the call through (we never hard-block calls).
 */
@RequiresApi(Build.VERSION_CODES.Q)
class JarvisCallScreeningService : CallScreeningService() {

    companion object {
        private const val TAG               = "JarvisCallScreening"
        private const val CHANNEL_SCREENING = "jarvis_call_screening"
        private const val NOTIF_ID          = 5001
    }

    override fun onScreenCall(callDetails: Call.Details) {
        Log.d(TAG, "onScreenCall: handle=${callDetails.handle}")

        val number = callDetails.handle?.schemeSpecificPart

        // Cache the number so TelephonyCallMonitor (which doesn't get numbers on API 31+)
        // can resolve the caller name via ContactsPhoneLookupResolver.
        if (!number.isNullOrBlank()) {
            IncomingCallNumberCache.put(number)
        }

        if (number.isNullOrBlank() || !hasContactsPermission()) {
            // Can't look up — allow through silently
            Log.d(TAG, "No number or no READ_CONTACTS permission — allowing call")
            respondToCall(callDetails, buildAllowResponse(silence = false))
            return
        }

        val contactName = lookupContact(number)

        if (contactName != null) {
            // Known contact — allow immediately without any screening delay
            Log.i(TAG, "Known contact: $contactName — allowing call immediately")
            respondToCall(callDetails, buildAllowResponse(silence = false))
        } else {
            // Unknown or private number — silence ringer + notify user + allow through
            Log.i(TAG, "Unknown caller ($number) — silencing and notifying")
            postScreeningNotification(number)
            respondToCall(callDetails, buildAllowResponse(silence = true))
        }
    }

    // ── Response builders ─────────────────────────────────────────────────────

    /**
     * Builds a [CallResponse] that allows the call.
     *
     * @param silence If true, the ringer is silenced (call still comes through —
     *                user sees it on screen but hears no ring for ~3 s then reverts).
     */
    private fun buildAllowResponse(silence: Boolean): CallResponse =
        CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSilenceCall(silence)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()

    // ── Contact lookup ────────────────────────────────────────────────────────

    /**
     * Looks up [phoneNumber] in the device contacts.
     * Returns the display name if found, or null if unknown.
     *
     * Requires READ_CONTACTS permission — checked before calling.
     */
    @SuppressLint("Range")
    private fun lookupContact(phoneNumber: String): String? {
        val uri: Uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )

        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )
            if (cursor != null && cursor.moveToFirst()) {
                cursor.getString(
                    cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Contact lookup failed for $phoneNumber: ${e.message}")
            null
        } finally {
            cursor?.close()
        }
    }

    // ── Permission helper ─────────────────────────────────────────────────────

    private fun hasContactsPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // ── Notification ──────────────────────────────────────────────────────────

    private fun postScreeningNotification(number: String) {
        ensureScreeningChannel()

        val body = "Unknown number: $number — screening call…"
        val notification = NotificationCompat.Builder(this, CHANNEL_SCREENING)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Unknown caller. Screening...")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted — skipping screening notification")
        }
    }

    private fun ensureScreeningChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_SCREENING) != null) return

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SCREENING,
                "Call Screening",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when Jarvis screens an unknown incoming call"
            }
        )
    }
}
