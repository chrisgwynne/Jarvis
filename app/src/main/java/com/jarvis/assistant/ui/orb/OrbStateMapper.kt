package com.jarvis.assistant.ui.orb

import com.jarvis.assistant.core.state.JarvisState

/**
 * Pure function — no coroutines, no Android dependencies.
 * Maps every JarvisState variant to the correct OrbVisualState.
 */
object OrbStateMapper {

    fun map(state: JarvisState): OrbVisualState = when (state) {
        is JarvisState.ServiceStopped    -> OrbVisualState.Dormant
        is JarvisState.IdleWake          -> OrbVisualState.WakeListening
        is JarvisState.WakeDetected      -> OrbVisualState.Activating
        is JarvisState.Listening         -> OrbVisualState.Listening()
        is JarvisState.Interrupted       -> OrbVisualState.Interrupted
        is JarvisState.Thinking          -> OrbVisualState.Processing()
        is JarvisState.ToolRunning       -> OrbVisualState.Processing(state.toolName)
        is JarvisState.Speaking          -> OrbVisualState.Speaking()
        is JarvisState.Silenced          -> OrbVisualState.Silencing
        is JarvisState.OfflineFallback   -> OrbVisualState.Degraded
        is JarvisState.MicUnavailable    -> OrbVisualState.MicBlocked
        // ── Call states ───────────────────────────────────────────────────────
        is JarvisState.IncomingCallAlert  -> OrbVisualState.Processing("call")
        is JarvisState.WaitingCallCommand -> OrbVisualState.Listening()
        is JarvisState.ExecutingCallAction -> OrbVisualState.Processing("call")
        // Phone has audio focus during CallActive; show mic-blocked to indicate
        // Jarvis is standing by but not processing.
        is JarvisState.CallActive         -> OrbVisualState.MicBlocked
        is JarvisState.CallRecovery       -> OrbVisualState.Activating
        // Outgoing call — same visual as mic-blocked: assistant is suspended.
        is JarvisState.OutgoingCallActive -> OrbVisualState.MicBlocked
    }
}
