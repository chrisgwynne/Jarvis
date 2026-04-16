package com.jarvis.assistant.call.integration

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import com.jarvis.assistant.call.CallEvent
import com.jarvis.assistant.call.CallInfo
import com.jarvis.assistant.call.CallMonitor
import com.jarvis.assistant.call.IncomingCallState
import com.jarvis.assistant.call.ResolutionConfidence
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.Executors

/**
 * TelephonyCallMonitor — listens to cellular call-state changes and emits
 * structured [CallEvent]s into a [SharedFlow].
 *
 * ── API VERSION STRATEGY ──────────────────────────────────────────────────
 *
 *   Android 12+ (API 31):
 *     Uses [TelephonyCallback.CallStateListener].  Modern, non-deprecated.
 *     LIMITATION: the phone number is NOT available in the callback (privacy
 *     change in API 31).  [CallInfo.incomingNumber] will be null; the contact
 *     resolver falls back to "Unknown caller".
 *
 *   Android 8–11 (API 26–30):
 *     Uses the deprecated [PhoneStateListener].  Functional and the phone
 *     number IS available in [onCallStateChanged].
 *
 * ── DEBOUNCING ───────────────────────────────────────────────────────────
 *
 *   Some OEMs fire duplicate state events (same state + number) within a
 *   short window.  Events whose (state, number) key matches the previous
 *   event within [DEBOUNCE_MS] are silently dropped.
 *
 * ── PERMISSIONS ──────────────────────────────────────────────────────────
 *
 *   READ_PHONE_STATE is required to register.
 *   [start] checks before registering and logs a warning if missing.
 *   No exception is thrown — monitoring simply does not start.
 */
class TelephonyCallMonitor(private val context: Context) : CallMonitor {

    companion object {
        private const val TAG         = "TelephonyCallMonitor"
        private const val DEBOUNCE_MS = 1_500L
    }

    private val telephonyManager: TelephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private val _events = MutableSharedFlow<CallEvent>(
        replay             = 0,
        extraBufferCapacity = 8,
        onBufferOverflow   = BufferOverflow.DROP_OLDEST
    )
    override val events: SharedFlow<CallEvent> = _events.asSharedFlow()

    // Debounce tracking
    private var lastKey  = ""
    private var lastTime = 0L

    // API 31+ callback reference (kept for unregister)
    private var telephonyCallback: TelephonyCallback? = null

    // API 26–30 listener reference (kept for unregister)
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    private var started = false

    // ── Incoming / outgoing call discrimination ───────────────────────────────
    //
    // Android's TelephonyManager.CALL_STATE_RINGING fires ONLY for incoming
    // calls.  For outgoing calls, the sequence is IDLE → OFFHOOK (immediately).
    // We track the previous raw state so OFFHOOK can be classified correctly:
    //
    //   IDLE → RINGING → OFFHOOK   incoming call, answered
    //   IDLE → RINGING → IDLE      incoming call, missed/declined
    //   IDLE → OFFHOOK             outgoing call placed
    //   OFFHOOK → IDLE             call ended (either direction)
    //
    // [previousRawState] starts as CALL_STATE_IDLE (the natural boot state).
    // [offhookWasIncoming] is armed true when RINGING fires and cleared on IDLE.
    private var previousRawState   = TelephonyManager.CALL_STATE_IDLE
    private var offhookWasIncoming = false

    // ── Public API ────────────────────────────────────────────────────────────

    override fun start() {
        if (started) return
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            Log.w(TAG, "READ_PHONE_STATE not granted — call monitoring disabled")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerApi31()
        } else {
            registerLegacy()
        }
        started = true
        Log.d(TAG, "Started (API ${Build.VERSION.SDK_INT})")
    }

    override fun stop() {
        if (!started) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
            telephonyCallback = null
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let {
                telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
            }
            phoneStateListener = null
        }
        started = false
        Log.d(TAG, "Stopped")
    }

    // ── API 31+ ───────────────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerApi31() {
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                // Phone number deliberately omitted by Android since API 31 (privacy).
                handleStateChange(state = state, rawNumber = null)
            }
        }
        telephonyManager.registerTelephonyCallback(
            Executors.newSingleThreadExecutor(),
            cb
        )
        telephonyCallback = cb
    }

    // ── API 26–30 ─────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun registerLegacy() {
        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleStateChange(
                    state     = state,
                    rawNumber = phoneNumber?.takeIf { it.isNotBlank() }
                )
            }
        }
        telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        phoneStateListener = listener
    }

    // ── Core event handling ───────────────────────────────────────────────────

    private fun handleStateChange(state: Int, rawNumber: String?) {
        val key = "$state|${rawNumber.orEmpty()}"
        val now = System.currentTimeMillis()

        if (key == lastKey && (now - lastTime) < DEBOUNCE_MS) {
            Log.d(TAG, "Debounced duplicate event: state=$state")
            return
        }
        lastKey  = key
        lastTime = now

        val callInfo = buildCallInfo(mapState(state), rawNumber, state)

        val event: CallEvent = when (state) {

            TelephonyManager.CALL_STATE_RINGING -> {
                // Always an incoming call — outgoing calls never produce RINGING.
                offhookWasIncoming = true   // arm: if OFFHOOK follows, it's incoming-answered
                previousRawState   = TelephonyManager.CALL_STATE_RINGING
                CallEvent.IncomingRinging(callInfo)
            }

            TelephonyManager.CALL_STATE_OFFHOOK -> {
                val incoming = (previousRawState == TelephonyManager.CALL_STATE_RINGING)
                offhookWasIncoming = incoming
                previousRawState   = TelephonyManager.CALL_STATE_OFFHOOK
                if (incoming) {
                    // Incoming call answered (by user or by Jarvis via TelecomManager)
                    CallEvent.CallAnswered(callInfo)
                } else {
                    // No prior RINGING → outgoing call placed via ACTION_CALL
                    CallEvent.OutgoingCallStarted(callInfo)
                }
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                val wasOutgoing = (previousRawState == TelephonyManager.CALL_STATE_OFFHOOK
                        && !offhookWasIncoming)
                offhookWasIncoming = false
                previousRawState   = TelephonyManager.CALL_STATE_IDLE
                if (wasOutgoing) {
                    CallEvent.OutgoingCallEnded(callInfo)
                } else {
                    // Covers: incoming declined, incoming missed, incoming call ended
                    CallEvent.CallEnded(callInfo)
                }
            }

            else -> {
                previousRawState = state
                CallEvent.CallStateChanged(callInfo)
            }
        }

        Log.d(TAG, "Event: ${event::class.simpleName}  state=$state  number=${rawNumber ?: "n/a"}")
        _events.tryEmit(event)
    }

    private fun buildCallInfo(
        callState : IncomingCallState,
        rawNumber : String?,
        rawState  : Int
    ): CallInfo = CallInfo(
        callState            = callState,
        incomingNumber       = rawNumber,
        resolvedDisplayName  = rawNumber ?: "Unknown caller",
        isKnownContact       = false,
        canAnswer            = hasPermission(Manifest.permission.ANSWER_PHONE_CALLS),
        canDecline           = hasPermission(Manifest.permission.ANSWER_PHONE_CALLS),
        resolutionConfidence = ResolutionConfidence.NONE,
        rawSourceMetadata    = mapOf(
            "raw_state"  to rawState.toString(),
            "api_level"  to Build.VERSION.SDK_INT.toString()
        ).let { m -> rawNumber?.let { m + ("raw_number" to it) } ?: m }
    )

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun mapState(state: Int): IncomingCallState = when (state) {
        TelephonyManager.CALL_STATE_RINGING -> IncomingCallState.RINGING
        TelephonyManager.CALL_STATE_OFFHOOK -> IncomingCallState.OFFHOOK
        TelephonyManager.CALL_STATE_IDLE    -> IncomingCallState.IDLE
        else                                -> IncomingCallState.UNKNOWN
    }

    private fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
