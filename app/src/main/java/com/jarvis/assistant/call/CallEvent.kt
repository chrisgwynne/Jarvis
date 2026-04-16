package com.jarvis.assistant.call

/**
 * CallEvent — sealed hierarchy emitted by [CallMonitor].
 *
 * All events carry an immutable [CallInfo] snapshot taken at observation time.
 * The coordinator collects these and reacts accordingly.
 */
sealed class CallEvent {

    /**
     * Incoming call is actively ringing.
     * Primary trigger for Jarvis call-handling interaction.
     */
    data class IncomingRinging(val callInfo: CallInfo) : CallEvent()

    /**
     * Call transitioned to OFFHOOK — either answered on the device by the user
     * before Jarvis could execute, or by Jarvis via [CallActionExecutor.answer].
     * Signals to [CallCoordinator] that it should move to [CallActive] and wait.
     */
    data class CallAnswered(val callInfo: CallInfo) : CallEvent()

    /**
     * Call cleared to IDLE — covers declined, missed, or ended after answer.
     * [callInfo.callState] distinguishes ENDED from IDLE / MISSED.
     */
    data class CallEnded(val callInfo: CallInfo) : CallEvent()

    /**
     * Any other telephony state change not covered above.
     * Logged but not acted on by the coordinator.
     */
    data class CallStateChanged(val callInfo: CallInfo) : CallEvent()

    /**
     * An outgoing call has been placed and is now dialing or ringing on the
     * remote end.  Detected when CALL_STATE_OFFHOOK appears without a prior
     * CALL_STATE_RINGING (which would indicate an incoming call).
     *
     * Note: Android's TelephonyManager does not distinguish between "dialing"
     * and "connected" for outgoing calls — OFFHOOK covers both phases.
     * [callInfo.incomingNumber] is null on API 31+ for privacy reasons.
     */
    data class OutgoingCallStarted(val callInfo: CallInfo) : CallEvent()

    /**
     * An outgoing call that was tracked via [OutgoingCallStarted] has ended.
     * The call may have been answered and then disconnected, or rejected by the
     * remote party, or cancelled by the user.
     */
    data class OutgoingCallEnded(val callInfo: CallInfo) : CallEvent()
}
