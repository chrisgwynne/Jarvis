package com.jarvis.assistant.call

import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
//  CallInfo — domain model for one incoming call event
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Call source type.
 * Extension point: add VOIP_WHATSAPP, VOIP_TEAMS etc. here when implementing
 * a VoIP monitor without touching [CallCoordinator] or [JarvisRuntime].
 */
enum class CallSourceType {
    CELLULAR
    // VOIP_WHATSAPP,  // future
}

/** Lifecycle phase of an incoming call from the Jarvis perspective. */
enum class IncomingCallState {
    /** Phone actively ringing. */
    RINGING,
    /** Call is connected (answered). */
    OFFHOOK,
    /** Call cleared — covers idle / declined / missed transitions. */
    IDLE,
    /** Call ended without being answered (detected retrospectively). */
    MISSED,
    /** Call ended after being answered. */
    ENDED,
    /** Unexpected state value from telephony. */
    UNKNOWN
}

/** Confidence in the resolved caller display name. */
enum class ResolutionConfidence {
    /** Exact match in device contacts. */
    HIGH,
    /** Number formatted or partial match. */
    LOW,
    /** No name found — "Unknown caller" fallback. */
    NONE
}

/**
 * CallInfo — immutable domain snapshot of one incoming call event.
 *
 * All telephony-specific details are abstracted here so the coordinator
 * and the UI never need to import android.telephony directly.
 */
data class CallInfo(
    /** Unique ID generated when the event is first observed. */
    val eventId: String = UUID.randomUUID().toString(),

    /** Always CELLULAR for this module. Extension point for future VoIP. */
    val sourceType: CallSourceType = CallSourceType.CELLULAR,

    /** Current phase of the call lifecycle. */
    val callState: IncomingCallState = IncomingCallState.UNKNOWN,

    /**
     * Raw incoming number as reported by telephony.
     * Null on Android 12+ (API 31) where the number is no longer provided in
     * the TelephonyCallback for privacy reasons.
     */
    val incomingNumber: String?,

    /**
     * Human-readable name resolved via the contact resolver.
     * Falls back to the formatted number, or "Unknown caller" if unavailable.
     */
    val resolvedDisplayName: String,

    /** True if the number was matched in the device's contacts database. */
    val isKnownContact: Boolean,

    /** Unix timestamp (ms) when this event was first observed. */
    val timestamp: Long = System.currentTimeMillis(),

    /** True when ANSWER_PHONE_CALLS permission is granted. */
    val canAnswer: Boolean,

    /** True when ANSWER_PHONE_CALLS permission is granted. */
    val canDecline: Boolean,

    /** How confident we are in [resolvedDisplayName]. */
    val resolutionConfidence: ResolutionConfidence,

    /**
     * Raw key/value metadata from the telephony layer — for debugging only.
     * Typical keys: "raw_state", "api_level", "raw_number".
     */
    val rawSourceMetadata: Map<String, String> = emptyMap()
)
