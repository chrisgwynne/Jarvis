package com.jarvis.assistant.call.integration

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log
import com.jarvis.assistant.call.CallActionExecutor
import com.jarvis.assistant.call.CallActionResult
import com.jarvis.assistant.notifications.JarvisNotificationListener

/**
 * TelecomCallActionExecutor — answers and declines incoming calls using
 * [TelecomManager].
 *
 * ── PERMISSIONS ──────────────────────────────────────────────────────────
 *
 *   Both actions require ANSWER_PHONE_CALLS (added in API 26).
 *   If the permission is missing, [CallActionResult.PermissionDenied] is
 *   returned and the action is not attempted.
 *
 * ── ANSWER ───────────────────────────────────────────────────────────────
 *
 *   Uses [TelecomManager.acceptRingingCall] which is the current
 *   recommended API for answering via an assistant.
 *
 * ── DECLINE ──────────────────────────────────────────────────────────────
 *
 *   Uses [TelecomManager.endCall].  This was deprecated in API 28 but
 *   remains the only public API that declines an incoming ringing call
 *   without requiring CallScreeningService (which is a different
 *   architecture entirely).  Suppressed with @Suppress("DEPRECATION").
 *
 * ── FAILURE HANDLING ─────────────────────────────────────────────────────
 *
 *   All exceptions are caught and normalised to [CallActionResult] variants.
 *   The service is never crashed by a telephony failure.
 */
class TelecomCallActionExecutor(private val context: Context) : CallActionExecutor {

    companion object {
        private const val TAG = "TelecomCallAction"
    }

    private val telecomManager: TelecomManager? =
        context.getSystemService(TelecomManager::class.java)

    // ── Public API ────────────────────────────────────────────────────────────

    override suspend fun answer(): CallActionResult {
        // Preferred path: fire the "Answer" PendingIntent from the incoming call
        // notification.  This works for regular calls AND WhatsApp/VoIP calls, and
        // does not require system permissions (Android Auto / Wear OS use the same
        // approach).  Requires Notification Access to have been granted.
        val answerIntent = JarvisNotificationListener.pollAnswerIntent()
        if (answerIntent != null) {
            return try {
                answerIntent.send()
                JarvisNotificationListener.clearCallActions()
                val asActivity = isActivityIntent(answerIntent)
                Log.d(TAG, "Answered via notification action PendingIntent (activity=$asActivity)")
                // Activity PendingIntents open the answer UI but do NOT auto-accept
                // (common on some WhatsApp builds).  The caller must verify via
                // CallEvent.CallAnswered before claiming the call is live.
                if (asActivity) CallActionResult.NeedsUserTap else CallActionResult.Success
            } catch (e: Exception) {
                Log.w(TAG, "Notification answer intent failed: ${e.message} — trying TelecomManager")
                answerViaTelecom()
            }
        }

        return answerViaTelecom()
    }

    /**
     * True if the PendingIntent will start an Activity (rather than a Service /
     * Broadcast).  Activity answer actions open the call UI but do not actually
     * accept the call — the user still has to tap the button.
     *
     * Only available on API 31+.  On older Androids we conservatively return
     * false so the flow treats the answer as straightforward.
     */
    private fun isActivityIntent(pi: PendingIntent): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) pi.isActivity else false
    } catch (_: Exception) {
        false
    }

    private fun answerViaTelecom(): CallActionResult {
        telecomManager ?: run {
            Log.e(TAG, "TelecomManager unavailable")
            return CallActionResult.NotAvailable
        }
        if (!hasAnswerPermission()) {
            Log.w(TAG, "ANSWER_PHONE_CALLS not granted — cannot answer")
            return CallActionResult.PermissionDenied
        }
        return try {
            @Suppress("DEPRECATION")
            telecomManager.acceptRingingCall()
            Log.d(TAG, "acceptRingingCall() → success")
            CallActionResult.Success
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException on acceptRingingCall: ${e.message}")
            CallActionResult.PermissionDenied
        } catch (e: Exception) {
            Log.e(TAG, "acceptRingingCall failed: ${e.message}", e)
            CallActionResult.Failure(e.message ?: "Unknown error")
        }
    }

    override suspend fun decline(): CallActionResult {
        // Preferred path: notification PendingIntent (works for VoIP + cellular).
        val declineIntent = JarvisNotificationListener.pollDeclineIntent()
        if (declineIntent != null) {
            return try {
                declineIntent.send()
                JarvisNotificationListener.clearCallActions()
                Log.d(TAG, "Declined via notification action PendingIntent")
                CallActionResult.Success
            } catch (e: Exception) {
                Log.w(TAG, "Notification decline intent failed: ${e.message} — trying TelecomManager")
                declineViaTelecom()
            }
        }

        return declineViaTelecom()
    }

    @Suppress("DEPRECATION")
    private fun declineViaTelecom(): CallActionResult {
        telecomManager ?: run {
            Log.e(TAG, "TelecomManager unavailable")
            return CallActionResult.NotAvailable
        }
        if (!hasAnswerPermission()) {
            Log.w(TAG, "ANSWER_PHONE_CALLS not granted — cannot decline")
            return CallActionResult.PermissionDenied
        }
        return try {
            telecomManager.endCall()
            Log.d(TAG, "endCall() → success")
            CallActionResult.Success
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException on endCall: ${e.message}")
            CallActionResult.PermissionDenied
        } catch (e: Exception) {
            Log.e(TAG, "endCall failed: ${e.message}", e)
            CallActionResult.Failure(e.message ?: "Unknown error")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun hasAnswerPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED
}
