package com.jarvis.assistant.core.state

/**
 * JarvisState — the complete set of runtime states for the voice pipeline.
 *
 * TRANSITIONS (legal paths):
 *
 *   SERVICE_STOPPED
 *       └─► IDLE_WAKE
 *
 *   IDLE_WAKE
 *       ├─► WAKE_DETECTED        (wake phrase or tap-to-speak)
 *       ├─► MIC_UNAVAILABLE      (audio record fails on start)
 *       └─► SERVICE_STOPPED      (stop() called)
 *
 *   WAKE_DETECTED
 *       └─► LISTENING            (chime played, pipeline opened)
 *
 *   LISTENING
 *       ├─► THINKING             (transcript received)
 *       ├─► IDLE_WAKE            (stop command or silence)
 *       ├─► MIC_UNAVAILABLE      (3 fast failures in a row)
 *       └─► INTERRUPTED          (barge-in during listen)
 *
 *   THINKING
 *       ├─► TOOL_RUNNING         (tool matched)
 *       ├─► SPEAKING             (LLM reply ready)
 *       ├─► IDLE_WAKE            (silence / stop)
 *       └─► OFFLINE_FALLBACK     (network call failed and no internet)
 *
 *   TOOL_RUNNING
 *       ├─► SPEAKING             (tool finished, feedback ready)
 *       ├─► THINKING             (tool result augments next LLM call)
 *       └─► IDLE_WAKE            (silence / stop)
 *
 *   SPEAKING
 *       ├─► LISTENING            (TTS done, loop continues)
 *       ├─► IDLE_WAKE            (stop command / silence)
 *       └─► INTERRUPTED          (barge-in while speaking)
 *
 *   INTERRUPTED
 *       ├─► LISTENING            (capture the interruption utterance)
 *       └─► IDLE_WAKE            (silence after interrupt)
 *
 *   SILENCED
 *       ├─► IDLE_WAKE            (silence() called — returns to wake mode)
 *       └─► SERVICE_STOPPED
 *
 *   OFFLINE_FALLBACK
 *       ├─► IDLE_WAKE            (network came back or silence)
 *       └─► SPEAKING             (local tool ran offline)
 *
 *   MIC_UNAVAILABLE
 *       ├─► IDLE_WAKE            (call ended / mic released)
 *       └─► SERVICE_STOPPED
 */
sealed class JarvisState {

    // ── Idle / Boot ───────────────────────────────────────────────────────────

    /** Service running, wake-word loop active, waiting for "Jarvis". */
    object IdleWake : JarvisState()

    /** Wake phrase (or tap-to-speak) just confirmed. Chime about to play. */
    object WakeDetected : JarvisState()

    // ── Active pipeline ───────────────────────────────────────────────────────

    /** SpeechRecognizer open; waiting for the user to speak. */
    object Listening : JarvisState()

    /** Transcript captured; LLM inference in progress. */
    object Thinking : JarvisState()

    /** A tool matched; executing device/web action. [toolName] shown in UI. */
    data class ToolRunning(val toolName: String) : JarvisState()

    /** TTS is playing the assistant's spoken response. */
    object Speaking : JarvisState()

    // ── Interruption ──────────────────────────────────────────────────────────

    /**
     * User started speaking while Jarvis was speaking.
     * TTS cancelled; about to capture interruption utterance.
     */
    object Interrupted : JarvisState()

    // ── Quiet / Off ───────────────────────────────────────────────────────────

    /**
     * User tapped Silence or said a stop command.
     * Service still running; transitions to IdleWake once clean.
     */
    object Silenced : JarvisState()

    // ── Error / Degraded ──────────────────────────────────────────────────────

    /** No internet; cloud LLM unreachable. Local tools still work. */
    object OfflineFallback : JarvisState()

    /** Mic held by phone call or another app. Waiting for release. */
    object MicUnavailable : JarvisState()

    /** Service fully stopped; pipeline is idle. */
    object ServiceStopped : JarvisState()

    // ── Incoming call ─────────────────────────────────────────────────────────

    /**
     * Incoming cellular call detected.
     * Jarvis is announcing the caller and prompting "Answer or decline?".
     * Entered via [JarvisStateMachine.forceTransition] from any state.
     */
    object IncomingCallAlert : JarvisState()

    /**
     * Announcement delivered; listening for "answer" or "decline" command.
     * Short (8 s) window; races against call-ended and call-answered events.
     */
    object WaitingCallCommand : JarvisState()

    /** Executing the answer or decline action via TelecomManager. */
    object ExecutingCallAction : JarvisState()

    /**
     * Call answered and connected. Phone call has audio focus.
     * Jarvis waits silently until the call ends, then transitions to
     * [CallRecovery].
     */
    object CallActive : JarvisState()

    /**
     * Post-call clean-up. Audio focus re-acquired if needed.
     * Transitions to [IdleWake] to resume wake-word detection.
     */
    object CallRecovery : JarvisState()

    // ── Outgoing call ─────────────────────────────────────────────────────────

    /**
     * An outgoing call placed by Jarvis is in progress.
     * The phone dialer owns the audio; Jarvis suspends all listening and
     * processing until the call ends (TelephonyCallMonitor emits
     * [CallEvent.OutgoingCallEnded]).
     *
     * Note: Android's TelephonyManager does not distinguish "dialing/ringing"
     * from "call connected" for outgoing calls — both are CALL_STATE_OFFHOOK.
     * This single state covers the entire outgoing call lifecycle.
     *
     * Entered via [JarvisStateMachine.forceTransition] from any state
     * (outgoing call is an external event, same pattern as [IncomingCallAlert]).
     * Exits to [CallRecovery] when [CallEvent.OutgoingCallEnded] is received.
     */
    object OutgoingCallActive : JarvisState()

    // ── Convenience ───────────────────────────────────────────────────────────

    /** True while the mic is open and the user could be speaking. */
    val isMicActive: Boolean
        get() = this is Listening || this is Interrupted || this is WaitingCallCommand

    /** True while audio output is active. */
    val isSpeaking: Boolean
        get() = this is Speaking

    /** True if we're in any active pipeline state (not idle/stopped). */
    val isPipelineActive: Boolean
        get() = this is WakeDetected || this is Listening || this is Thinking ||
                this is ToolRunning || this is Speaking   || this is Interrupted

    /**
     * True while the runtime is inside any call-handling state — incoming or outgoing.
     * Used by [JarvisRuntime] to:
     *   • suppress audio-focus-loss silence() triggers during phone calls
     *   • gate reminder delivery to notification-only mode
     *   • prevent duplicate wake-word restarts
     */
    val isCallState: Boolean
        get() = this is IncomingCallAlert    || this is WaitingCallCommand ||
                this is ExecutingCallAction  || this is CallActive         ||
                this is OutgoingCallActive   || this is CallRecovery

    /** Maps to the legacy JarvisService.State enum for broadcast to UI. */
    fun toLegacyState(): String = when (this) {
        is Listening, is Interrupted,
        is WaitingCallCommand                            -> "LISTENING"
        is Thinking, is ToolRunning,
        is IncomingCallAlert, is ExecutingCallAction     -> "PROCESSING"
        is Speaking                                      -> "SPEAKING"
        is OfflineFallback                               -> "PROCESSING"
        // Outgoing call — assistant is suspended; UI shows IDLE so buttons
        // are not shown in a misleading "active listening" state.
        is OutgoingCallActive                            -> "IDLE"
        else                                             -> "IDLE"
    }
}
