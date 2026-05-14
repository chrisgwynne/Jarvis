package com.jarvis.assistant.tools.device.messaging

import com.jarvis.assistant.tools.ContactLookup
import com.jarvis.assistant.tools.framework.ToolResult

/**
 * MessageDeliveryAdapter — the *only* thing that differs between SMS and
 * WhatsApp once parsing, contact lookup and confidence handling are done.
 *
 * Everything before this point — wake → STT → TranscriptCorrector →
 * AttentionGate → MessageIntentParser → ContactLookup → ConfirmationGate
 * — is identical for both channels.  This adapter wraps the last 1-2
 * Android API calls that take the resolved contact and the body and
 * actually send / open the message.
 *
 * Adapters MUST return promptly (≤ 3 s).  Long Accessibility waits or
 * blocking pending-result loops are explicitly forbidden — Jarvis must
 * speak its confirmation snappily and never appear to "think for 30
 * seconds" while a side-effect we can't observe completes.
 */
interface MessageDeliveryAdapter {

    /** Stable identifier used in logs. */
    val channelName: String

    /**
     * Send the message.  Returns a [ToolResult] suitable for the runtime
     * to either speak directly or fail-fast on.
     *
     * Contract:
     *  - Must return within ~3 seconds even if the underlying transport
     *    times out.  No infinite waits, no 30-second Accessibility loops.
     *  - On success: `ToolResult.Success` with a short spoken feedback
     *    that names the contact (the runtime speaks it verbatim).
     *  - On failure: `ToolResult.Failure` with a user-facing diagnostic.
     */
    suspend fun send(contact: ContactLookup.Contact, body: String): ToolResult
}
