package com.jarvis.assistant.call

/**
 * CallControlResult — structured result of an attempt to programmatically
 * end an active or ringing outgoing call.
 *
 * Every case includes a short [humanMessage] that can be spoken directly
 * to the user.  No exceptions escape from the controller — all outcomes
 * are normalised to this type.
 *
 * HOW TO CONSUME:
 *   when (result) {
 *       is Success      -> speak(result.humanMessage)
 *       is NoActiveCall -> speak(result.humanMessage)
 *       is Unsupported  -> speak(result.humanMessage)   // honest failure
 *       is Failed       -> log(result.debugReason); speak(result.humanMessage)
 *   }
 */
sealed class CallControlResult {

    /** The call-end request was dispatched without error. */
    data class Success(
        val humanMessage : String = "Call ended.",
        val debugReason  : String = "TelecomManager.endCall() succeeded",
        val timestamp    : Long   = System.currentTimeMillis()
    ) : CallControlResult()

    /**
     * No outgoing call was tracked at the time of the request.
     * Either the call was never placed, already ended before the command
     * arrived, or OutgoingCallController was never notified.
     */
    data class NoActiveCall(
        val humanMessage : String = "There isn't a call to end.",
        val timestamp    : Long   = System.currentTimeMillis()
    ) : CallControlResult()

    /**
     * The device or permission setup does not allow programmatic call ending.
     *
     * Common reasons (see [debugReason]):
     *   • ANSWER_PHONE_CALLS permission not granted
     *   • TelecomManager unavailable (rare — device-specific)
     *   • API level < 28 (endCall() was added in API 28)
     *
     * This is distinct from [Failed]: the operation was never attempted,
     * not attempted-and-threw.
     */
    data class Unsupported(
        val humanMessage : String = "I can't end the call on this setup.",
        val debugReason  : String,
        val timestamp    : Long   = System.currentTimeMillis()
    ) : CallControlResult()

    /**
     * The operation was attempted but threw at the telephony layer.
     * The API was available but something went wrong at runtime.
     */
    data class Failed(
        val humanMessage : String = "I wasn't able to end the call.",
        val debugReason  : String,
        val timestamp    : Long   = System.currentTimeMillis()
    ) : CallControlResult()
}
