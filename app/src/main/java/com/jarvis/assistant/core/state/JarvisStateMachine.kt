package com.jarvis.assistant.core.state

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.reflect.KClass

/**
 * JarvisStateMachine — validates and records every state transition.
 *
 * Thread safety: StateFlow.value updates are atomic. All callers should be
 * on the Main dispatcher (consistent with the rest of the audio pipeline).
 *
 * Usage:
 *   val ok = machine.transition(JarvisState.Listening)
 *   if (!ok) { /* illegal transition — handle or log */ }
 *
 *   // Force-transition without validation (use for service shutdown only):
 *   machine.forceTransition(JarvisState.ServiceStopped)
 */
class JarvisStateMachine {

    companion object {
        private const val TAG = "StateMachine"

        /**
         * Legal transition graph.
         * Key   = current state class
         * Value = set of state classes we can move to from there
         */
        private val TRANSITIONS: Map<KClass<out JarvisState>, Set<KClass<out JarvisState>>> = mapOf(
            JarvisState.ServiceStopped::class to setOf(
                JarvisState.IdleWake::class
            ),
            JarvisState.IdleWake::class to setOf(
                JarvisState.WakeDetected::class,
                JarvisState.MicUnavailable::class,
                JarvisState.ServiceStopped::class
            ),
            JarvisState.WakeDetected::class to setOf(
                JarvisState.Listening::class,
                JarvisState.IdleWake::class         // edge: wake detected but chime failed
            ),
            JarvisState.Listening::class to setOf(
                JarvisState.Thinking::class,
                JarvisState.IdleWake::class,
                JarvisState.MicUnavailable::class,
                JarvisState.Interrupted::class,
                JarvisState.Silenced::class
            ),
            JarvisState.Thinking::class to setOf(
                JarvisState.ToolRunning::class,
                JarvisState.Speaking::class,
                JarvisState.IdleWake::class,
                JarvisState.OfflineFallback::class,
                JarvisState.Silenced::class
            ),
            JarvisState.ToolRunning::class to setOf(
                JarvisState.Speaking::class,
                JarvisState.Thinking::class,        // tool result feeds next LLM call
                JarvisState.IdleWake::class,
                JarvisState.Silenced::class
            ),
            JarvisState.Speaking::class to setOf(
                JarvisState.Listening::class,
                JarvisState.IdleWake::class,
                JarvisState.Interrupted::class,
                JarvisState.Silenced::class
            ),
            JarvisState.Interrupted::class to setOf(
                JarvisState.Listening::class,
                JarvisState.IdleWake::class,
                JarvisState.Silenced::class
            ),
            JarvisState.Silenced::class to setOf(
                JarvisState.IdleWake::class,
                JarvisState.ServiceStopped::class
            ),
            JarvisState.OfflineFallback::class to setOf(
                JarvisState.IdleWake::class,
                JarvisState.Speaking::class,        // local tool replied
                JarvisState.Thinking::class         // network came back
            ),
            JarvisState.MicUnavailable::class to setOf(
                JarvisState.IdleWake::class,
                JarvisState.ServiceStopped::class
            ),

            // ── Incoming call sub-pipeline ────────────────────────────────
            // IncomingCallAlert is entered via forceTransition() from any
            // state (call is an external interrupt, same pattern as
            // forceTransition(ServiceStopped) for shutdown).  All subsequent
            // steps in the call pipeline use normal validated transitions.

            JarvisState.IncomingCallAlert::class to setOf(
                JarvisState.WaitingCallCommand::class,
                JarvisState.CallActive::class,    // answered on device during announcement
                JarvisState.CallRecovery::class   // call ended during announcement
            ),
            JarvisState.WaitingCallCommand::class to setOf(
                JarvisState.ExecutingCallAction::class,
                JarvisState.CallActive::class,    // answered on device during listen window
                JarvisState.CallRecovery::class   // call ended during listen window
            ),
            JarvisState.ExecutingCallAction::class to setOf(
                JarvisState.CallActive::class,    // answer succeeded
                JarvisState.CallRecovery::class   // decline, or action failed
            ),
            JarvisState.CallActive::class to setOf(
                JarvisState.CallRecovery::class   // call ended
            ),
            JarvisState.CallRecovery::class to setOf(
                JarvisState.IdleWake::class,      // normal recovery path
                JarvisState.ServiceStopped::class // service stopped during recovery
            ),

            // ── Outgoing call sub-pipeline ────────────────────────────────
            // OutgoingCallActive is entered via forceTransition() from any
            // state (symmetrical to IncomingCallAlert — both are external
            // interrupts that can arrive from an arbitrary pipeline state).
            // Exits to CallRecovery when TelephonyCallMonitor signals IDLE.

            JarvisState.OutgoingCallActive::class to setOf(
                JarvisState.CallRecovery::class   // outgoing call ended → resume
            )
        )
    }

    private val _state = MutableStateFlow<JarvisState>(JarvisState.ServiceStopped)

    /** Observe the current state. Emits on every transition. */
    val state: StateFlow<JarvisState> = _state.asStateFlow()

    /** Snapshot of the current state — safe to read from any thread. */
    val current: JarvisState get() = _state.value

    /**
     * Attempt a validated transition.
     * Returns true if the transition is legal and was applied.
     * Returns false (and logs a warning) if the transition is illegal.
     */
    fun transition(to: JarvisState): Boolean {
        val from = _state.value
        if (from == to) return true   // no-op: already in target state

        val allowed = TRANSITIONS[from::class] ?: emptySet()
        return if (to::class in allowed) {
            _state.value = to
            Log.d(TAG, "${from::class.simpleName} → ${to::class.simpleName}")
            true
        } else {
            Log.w(TAG, "ILLEGAL: ${from::class.simpleName} → ${to::class.simpleName} (ignored)")
            false
        }
    }

    /**
     * Force a transition regardless of the transition graph.
     * Use only for emergency shutdown (service destroyed) or test setup.
     */
    fun forceTransition(to: JarvisState) {
        val from = _state.value
        Log.w(TAG, "FORCED: ${from::class.simpleName} → ${to::class.simpleName}")
        _state.value = to
    }

    /** Convenience: are we currently in state [T]? */
    inline fun <reified T : JarvisState> isIn(): Boolean = state.value is T

    /**
     * Convenience: transition AND run [block] only if the transition succeeded.
     */
    inline fun transitionAnd(to: JarvisState, block: () -> Unit): Boolean {
        return if (transition(to)) { block(); true } else false
    }
}
