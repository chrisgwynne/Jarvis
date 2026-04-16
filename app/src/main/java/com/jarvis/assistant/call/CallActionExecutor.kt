package com.jarvis.assistant.call

/**
 * Result of an answer or decline attempt via [CallActionExecutor].
 */
sealed class CallActionResult {
    /** Action completed successfully. */
    object Success : CallActionResult()

    /**
     * The answer PendingIntent was fired but opens the caller's UI rather than
     * auto-accepting (common on some WhatsApp builds — the "Answer" action is
     * an Activity intent, not a Service).  The call is NOT yet live; the user
     * must tap the answer button on screen.
     */
    object NeedsUserTap : CallActionResult()

    /** Action failed for a known, non-permission reason. */
    data class Failure(val reason: String) : CallActionResult()

    /** ANSWER_PHONE_CALLS permission is not granted. */
    object PermissionDenied : CallActionResult()

    /** TelecomManager is unavailable on this device (rare). */
    object NotAvailable : CallActionResult()
}

/**
 * CallActionExecutor — answers or declines a ringing cellular call.
 *
 * Production implementation: [TelecomCallActionExecutor]
 *
 * Contract:
 *  • Never throws — wraps all exceptions and returns a [CallActionResult].
 *  • Safe to call even if no call is currently ringing — returns [Failure].
 *  • Both methods are suspend functions to allow IO dispatch if needed.
 */
interface CallActionExecutor {
    /** Answer the currently ringing call. */
    suspend fun answer(): CallActionResult

    /** Decline / end the currently ringing call. */
    suspend fun decline(): CallActionResult
}
