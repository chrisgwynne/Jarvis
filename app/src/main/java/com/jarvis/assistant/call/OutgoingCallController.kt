package com.jarvis.assistant.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log

/**
 * OutgoingCallController — tracks whether an outgoing call is currently active
 * and provides a single entry-point to end it programmatically.
 *
 * ── STATE MANAGEMENT ─────────────────────────────────────────────────────────
 *
 *   [isOutgoingCallActive] is set to true by JarvisRuntime when it receives an
 *   [CallEvent.OutgoingCallStarted] event from TelephonyCallMonitor, and reset
 *   to false on [CallEvent.OutgoingCallEnded].
 *
 *   This flag is the authoritative record that an outgoing call is in progress.
 *   It must be updated on the Main dispatcher to stay consistent with the
 *   rest of the runtime.
 *
 * ── END-CALL SUPPORT ─────────────────────────────────────────────────────────
 *
 *   Uses [TelecomManager.endCall], which requires ANSWER_PHONE_CALLS (API 26+)
 *   and was added in API 28.  It was deprecated in API 32 but remains the only
 *   public API for ending calls without registering a full InCallService.
 *   As of API 34 it still works on all tested devices.
 *
 *   On unsupported setups, [endCall] returns [CallControlResult.Unsupported]
 *   with a clear debug reason — never a silent no-op.
 *
 * ── FAILURE HANDLING ─────────────────────────────────────────────────────────
 *
 *   Every outcome is a [CallControlResult].  No exceptions escape.
 */
class OutgoingCallController(private val context: Context) {

    companion object {
        private const val TAG = "OutgoingCallController"
    }

    /**
     * True while an outgoing call is believed to be active.
     * Updated by [notifyCallStarted] / [notifyCallEnded] from JarvisRuntime.
     */
    @Volatile var isOutgoingCallActive: Boolean = false
        private set

    /** Called by JarvisRuntime when [CallEvent.OutgoingCallStarted] is received. */
    fun notifyCallStarted() {
        isOutgoingCallActive = true
        Log.d(TAG, "Outgoing call started — controller armed")
    }

    /** Called by JarvisRuntime when [CallEvent.OutgoingCallEnded] is received. */
    fun notifyCallEnded() {
        isOutgoingCallActive = false
        Log.d(TAG, "Outgoing call ended — controller disarmed")
    }

    /**
     * Attempt to end the current outgoing call.
     *
     * Outcomes:
     *   [CallControlResult.NoActiveCall]  — no call is tracked (guard check fails)
     *   [CallControlResult.Unsupported]   — permission missing, API < 28, or no TelecomManager
     *   [CallControlResult.Success]       — [TelecomManager.endCall] was dispatched
     *   [CallControlResult.Failed]        — API called but threw unexpectedly
     *
     * This method is synchronous — [TelecomManager.endCall] itself is fire-and-forget.
     * The actual call state change will arrive later via [TelephonyCallMonitor].
     */
    @Suppress("DEPRECATION")
    fun endCall(): CallControlResult {
        if (!isOutgoingCallActive) {
            Log.d(TAG, "endCall() — no active outgoing call tracked")
            return CallControlResult.NoActiveCall()
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.w(TAG, "endCall() requires API 28+ — device is API ${Build.VERSION.SDK_INT}")
            return CallControlResult.Unsupported(
                debugReason = "TelecomManager.endCall() requires API 28+ (device=${Build.VERSION.SDK_INT})"
            )
        }

        if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "endCall() — ANSWER_PHONE_CALLS permission not granted")
            return CallControlResult.Unsupported(
                debugReason = "ANSWER_PHONE_CALLS permission not granted"
            )
        }

        val telecom = context.getSystemService(TelecomManager::class.java)
        if (telecom == null) {
            Log.e(TAG, "endCall() — TelecomManager is null (device-specific issue)")
            return CallControlResult.Unsupported(
                debugReason = "TelecomManager unavailable"
            )
        }

        return try {
            // endCall() is deprecated in API 32 but no standard replacement
            // exists in the public SDK without a registered InCallService.
            // Functionally confirmed working on API 26–34.
            telecom.endCall()
            Log.i(TAG, "endCall() dispatched successfully")
            CallControlResult.Success()
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException calling endCall(): ${e.message}")
            // Can happen if permission was revoked after the check
            CallControlResult.Unsupported(
                debugReason = "SecurityException: ${e.message}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected exception calling endCall(): ${e.message}", e)
            CallControlResult.Failed(
                debugReason = e.message ?: "unknown telephony error"
            )
        }
    }
}
