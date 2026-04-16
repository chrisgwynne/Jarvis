package com.jarvis.assistant.call

import android.util.Log
import com.jarvis.assistant.audio.SpeechCapture
import com.jarvis.assistant.audio.TtsEngine
import com.jarvis.assistant.core.state.JarvisState
import com.jarvis.assistant.core.state.JarvisStateMachine
import com.jarvis.assistant.notifications.JarvisNotificationListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * CallCoordinator — owns the end-to-end incoming call interaction.
 *
 * ── FLOW ──────────────────────────────────────────────────────────────────
 *
 *   IncomingRinging
 *     1. Resolve caller name via [CallResolver]
 *     2. forceTransition → IncomingCallAlert
 *     3. Speak announcement ("Chris is calling.  Answer or decline?")
 *     4. [WaitingCallCommand] — race: speech command  vs  call ends  vs  8 s timeout
 *     5. Parse command
 *     6. [ExecutingCallAction] — call [CallActionExecutor.answer] or [.decline]
 *     7. Speak brief confirmation ("Answered." / "Declined." / "Call ended.")
 *     8. [CallActive] if answered — wait for call to end
 *     9. [CallRecovery] — signals JarvisRuntime to return to wake-word mode
 *
 * ── RACING ────────────────────────────────────────────────────────────────
 *
 *   The listen window races three events using [kotlinx.coroutines.selects.select]:
 *     a) speechCapture.listen() returns a transcript
 *     b) callEndedDeferred completes (caller hung up → IDLE)
 *     c) callAnsweredExternallyDeferred completes (answered on device → OFFHOOK)
 *
 *   If (a) wins → parse the command and execute.
 *   If (b) wins → "Call ended." and recover.
 *   If (c) wins → transition to [CallActive], await end, then recover.
 *   If the 8 s timeout fires → no intervention, recover.
 *
 * ── AUDIO FOCUS ───────────────────────────────────────────────────────────
 *
 *   [JarvisRuntime] abandons audio focus when [syncState] reports
 *   [JarvisState.CallActive], handing the audio route to the phone call.
 *
 * ── TESTABILITY ──────────────────────────────────────────────────────────
 *
 *   All Android API calls are injected via interfaces. The coordinator
 *   has no direct android.telephony or android.telecom imports.
 */
class CallCoordinator(
    private val ttsEngine:     TtsEngine,
    private val speechCapture: SpeechCapture,
    private val machine:       JarvisStateMachine,
    private val resolver:      CallResolver,
    private val executor:      CallActionExecutor,
    private val syncState:     (JarvisState) -> Unit,
    private val scope:         CoroutineScope
) {

    companion object {
        private const val TAG              = "CallCoordinator"
        private const val LISTEN_TIMEOUT   = 8_000L   // ms for "answer / decline?" window
        private const val RETRY_TIMEOUT    = 5_000L   // ms for second-chance listen
        // How long to wait for CALL_STATE_OFFHOOK after firing the answer
        // intent before concluding that the intent didn't actually pick up.
        private const val ANSWER_VERIFY_MS = 2_500L
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Handle an incoming ringing call from start to finish.
     *
     * Suspends until the interaction is fully resolved — action taken, call
     * cleared, or timed out.  After this returns, [JarvisRuntime] transitions
     * back to [JarvisState.IdleWake] and restarts wake-word detection.
     *
     * @param event       The [CallEvent.IncomingRinging] that triggered handling.
     * @param callEvents  The monitor's live event flow — used to detect if the
     *                    call ends or is answered on-device during our window.
     */
    suspend fun handleIncomingCall(
        event:      CallEvent.IncomingRinging,
        callEvents: Flow<CallEvent>
    ) {
        Log.d(TAG, "handleIncomingCall: number=${event.callInfo.incomingNumber ?: "n/a"}")

        // Two deferreds track the call lifecycle independently.
        // callEndedDeferred    → IDLE  (call over; covers declined / missed / ended)
        // callAnsweredExternal → OFFHOOK without Jarvis (user picked up manually)
        val callEndedDeferred        = CompletableDeferred<Unit>()
        val callAnsweredExternal     = CompletableDeferred<Unit>()

        // Start monitoring immediately so we never miss a fast transition.
        // We must keep collecting until BOTH CallAnswered AND CallEnded have
        // arrived (or the job is cancelled) — otherwise, on the normal
        // "answered → user hangs up" path, CallEnded fires after the monitor
        // has already exited on CallAnswered, and callEndedDeferred.await()
        // hangs forever in executeAction.
        val monitorJob = scope.launch {
            try {
                callEvents
                    .filter { it is CallEvent.CallEnded || it is CallEvent.CallAnswered }
                    .collect { event ->
                        when (event) {
                            is CallEvent.CallEnded    -> {
                                if (!callEndedDeferred.isCompleted) callEndedDeferred.complete(Unit)
                            }
                            is CallEvent.CallAnswered -> {
                                if (!callAnsweredExternal.isCompleted) callAnsweredExternal.complete(Unit)
                            }
                            else -> {}
                        }
                        // handleIncomingCall's finally block cancels this job,
                        // so we just keep collecting until that happens.
                    }
            } catch (_: Exception) {
                // Cancelled when finally block runs — expected
            }
        }

        try {
            // ── 1. Resolve caller name ─────────────────────────────────────
            val resolved = resolver.resolve(event.callInfo.incomingNumber)
            val callInfo = event.callInfo.copy(
                resolvedDisplayName  = resolved.displayName,
                isKnownContact       = resolved.isKnown,
                resolutionConfidence = resolved.confidence
            )

            // ── 2. Announce ────────────────────────────────────────────────
            machine.forceTransition(JarvisState.IncomingCallAlert)
            syncState(JarvisState.IncomingCallAlert)
            ttsEngine.speak(buildAnnouncement(callInfo))

            // Fast exit: call ended during announcement
            if (callEndedDeferred.isCompleted) {
                Log.d(TAG, "Call ended during announcement")
                ttsEngine.speak("Call ended.")
                transitionToRecovery()
                return
            }

            // Fast path: answered on device during announcement
            if (callAnsweredExternal.isCompleted) {
                Log.d(TAG, "Answered on device during announcement")
                enterCallActiveAndWait(callEndedDeferred)
                return
            }

            // ── 3. Listen window ───────────────────────────────────────────
            machine.transition(JarvisState.WaitingCallCommand)
            syncState(JarvisState.WaitingCallCommand)

            val transcript = listenForCommand(callEndedDeferred, callAnsweredExternal)

            // ── 4. Dispatch result ─────────────────────────────────────────
            when {
                callEndedDeferred.isCompleted && transcript == null -> {
                    Log.d(TAG, "Call ended during listen window")
                    ttsEngine.speak("Call ended.")
                    transitionToRecovery()
                }

                callAnsweredExternal.isCompleted && transcript == null -> {
                    Log.d(TAG, "Answered on device during listen window")
                    enterCallActiveAndWait(callEndedDeferred)
                }

                transcript.isNullOrBlank() -> {
                    // Timeout — no user response; leave call to ring
                    Log.d(TAG, "Listen timeout — no intervention")
                    transitionToRecovery()
                }

                else -> {
                    val action = parseCommand(transcript)
                    Log.d(TAG, "Command: \"$transcript\" → $action")
                    machine.transition(JarvisState.ExecutingCallAction)
                    syncState(JarvisState.ExecutingCallAction)
                    executeAction(action, callInfo, callEndedDeferred, callAnsweredExternal)
                }
            }

        } finally {
            monitorJob.cancel()
            // Drop the cached caller-name so the next call starts fresh.
            JarvisNotificationListener.clearCallerName()
        }
    }

    // ── Interaction helpers ───────────────────────────────────────────────────

    /**
     * Race speech capture against call-end and call-answered-externally events.
     * Returns null on timeout or when either lifecycle event fires first.
     */
    private suspend fun listenForCommand(
        callEndedDeferred:    CompletableDeferred<Unit>,
        callAnsweredDeferred: CompletableDeferred<Unit>
    ): String? {
        // async jobs are launched on the coordinator's scope so they are not
        // automatically cancelled when withTimeoutOrNull fires — we cancel them
        // explicitly in the finally block.
        val listenJob = scope.async(Dispatchers.Main) { speechCapture.listen() }
        return try {
            withTimeoutOrNull(LISTEN_TIMEOUT) {
                select<String?> {
                    listenJob.onAwait            { it   }
                    callEndedDeferred.onAwait    { null }
                    callAnsweredDeferred.onAwait { null }
                }
            }
        } finally {
            listenJob.cancel()  // stops the SpeechRecognizer if it was still running
        }
    }

    /**
     * Transition to [CallActive], wait for the call to end, then recover.
     */
    private suspend fun enterCallActiveAndWait(callEndedDeferred: CompletableDeferred<Unit>) {
        machine.transition(JarvisState.CallActive)
        syncState(JarvisState.CallActive)
        callEndedDeferred.await()   // blocks until IDLE fires
        Log.d(TAG, "Call ended — recovering")
        ttsEngine.speak("Call ended.")
        transitionToRecovery()
    }

    private fun transitionToRecovery() {
        machine.transition(JarvisState.CallRecovery)
        syncState(JarvisState.CallRecovery)
    }

    // ── Command execution ─────────────────────────────────────────────────────

    private suspend fun executeAction(
        action:             CallCommandAction,
        callInfo:           CallInfo,
        callEndedDeferred:  CompletableDeferred<Unit>,
        callAnsweredDeferred: CompletableDeferred<Unit>
    ) {
        when (action) {

            CallCommandAction.ANSWER -> {
                if (!callInfo.canAnswer) {
                    ttsEngine.speak("I don't have permission to answer calls.")
                    transitionToRecovery()
                    return
                }
                when (val result = executor.answer()) {
                    CallActionResult.Success -> {
                        // Firing the intent doesn't guarantee the call state
                        // actually moves to OFFHOOK — verify by waiting for
                        // [CallEvent.CallAnswered] before claiming success.
                        // If the call app's "Answer" action is an Activity
                        // intent it'll open the UI without picking up.
                        val answered = withTimeoutOrNull(ANSWER_VERIFY_MS) {
                            callAnsweredDeferred.await()
                        }
                        if (answered != null || callAnsweredDeferred.isCompleted) {
                            machine.transition(JarvisState.CallActive)
                            syncState(JarvisState.CallActive)
                            ttsEngine.speak("Answered.")
                            callEndedDeferred.await()
                            ttsEngine.speak("Call ended.")
                            transitionToRecovery()
                        } else {
                            // Intent fired but the call didn't go live.
                            Log.w(TAG, "Answer fired but no OFFHOOK within ${ANSWER_VERIFY_MS}ms")
                            ttsEngine.speak(
                                "I tried to answer but it didn't go through. " +
                                "Tap the answer button on screen."
                            )
                            transitionToRecovery()
                        }
                    }
                    CallActionResult.NeedsUserTap -> {
                        // Activity PendingIntent — opened the dialer UI, not picked up.
                        val source = JarvisNotificationListener.peekCallerSource()
                        val app    = friendlyAppName(source)
                        ttsEngine.speak("$app is open on screen — tap answer to pick up.")
                        transitionToRecovery()
                    }
                    CallActionResult.PermissionDenied -> {
                        ttsEngine.speak("I need the answer calls permission.")
                        transitionToRecovery()
                    }
                    CallActionResult.NotAvailable -> {
                        ttsEngine.speak("Call answering is not available on this device.")
                        transitionToRecovery()
                    }
                    is CallActionResult.Failure -> {
                        Log.w(TAG, "Answer failed: ${result.reason}")
                        ttsEngine.speak("That didn't work — tap answer on screen.")
                        transitionToRecovery()
                    }
                }
            }

            CallCommandAction.DECLINE -> {
                if (!callInfo.canDecline) {
                    ttsEngine.speak("I don't have permission to decline calls.")
                    transitionToRecovery()
                    return
                }
                when (executor.decline()) {
                    CallActionResult.Success         -> ttsEngine.speak("Declined.")
                    CallActionResult.NeedsUserTap    -> ttsEngine.speak("Tap decline on screen to end it.")
                    CallActionResult.PermissionDenied -> ttsEngine.speak("I need permission to decline calls.")
                    CallActionResult.NotAvailable    -> ttsEngine.speak("Call decline is not available.")
                    is CallActionResult.Failure      -> ttsEngine.speak("Couldn't decline the call.")
                }
                transitionToRecovery()
            }

            CallCommandAction.UNKNOWN -> {
                // Give the user one more chance
                ttsEngine.speak("Say answer or decline.")
                machine.transition(JarvisState.WaitingCallCommand)
                syncState(JarvisState.WaitingCallCommand)

                val retry = withTimeoutOrNull(RETRY_TIMEOUT) { speechCapture.listen() }
                val retryAction = if (!retry.isNullOrBlank()) parseCommand(retry)
                                  else CallCommandAction.NO_RESPONSE

                if (retryAction == CallCommandAction.ANSWER || retryAction == CallCommandAction.DECLINE) {
                    machine.transition(JarvisState.ExecutingCallAction)
                    syncState(JarvisState.ExecutingCallAction)
                    executeAction(retryAction, callInfo, callEndedDeferred, callAnsweredDeferred)
                } else {
                    transitionToRecovery()
                }
            }

            CallCommandAction.NO_RESPONSE -> transitionToRecovery()
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private fun buildAnnouncement(callInfo: CallInfo): String =
        "${callInfo.resolvedDisplayName} is calling.  Answer or decline?"

    /** Convert a call-notification package name into a friendly spoken name. */
    private fun friendlyAppName(pkg: String?): String = when (pkg) {
        null -> "The call"
        "com.whatsapp"                  -> "WhatsApp"
        "com.whatsapp.w4b"               -> "WhatsApp Business"
        "com.facebook.orca"              -> "Messenger"
        "org.telegram.messenger"         -> "Telegram"
        "com.skype.raider"               -> "Skype"
        "com.microsoft.teams"            -> "Teams"
        "com.google.android.dialer",
        "com.android.dialer"             -> "The dialer"
        "com.google.android.apps.tachyon" -> "Google Meet"
        "com.discord"                    -> "Discord"
        "us.zoom.videomeetings"          -> "Zoom"
        else -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    private fun parseCommand(transcript: String): CallCommandAction {
        val s = transcript.lowercase().trim()
        return when {
            s.contains("answer")  || s.contains("pick up")  ||
            s.contains("accept")  || s == "yes"              ||
            s == "yeah"           || s.contains("get it")    -> CallCommandAction.ANSWER

            s.contains("decline") || s.contains("reject")   ||
            s.contains("ignore")  || s.contains("dismiss")  ||
            s == "no"             || s.contains("hang")      -> CallCommandAction.DECLINE

            s.isBlank()                                      -> CallCommandAction.NO_RESPONSE

            else                                             -> CallCommandAction.UNKNOWN
        }
    }
}

// ── Supporting enum ───────────────────────────────────────────────────────────

/** Parsed user intent from a voice command in the call interaction window. */
internal enum class CallCommandAction {
    ANSWER,
    DECLINE,
    /** Command heard but not recognised — give one more chance. */
    UNKNOWN,
    /** No transcript returned (silence / timeout). */
    NO_RESPONSE
}
