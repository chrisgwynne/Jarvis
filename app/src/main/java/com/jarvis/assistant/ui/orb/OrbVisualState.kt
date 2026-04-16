package com.jarvis.assistant.ui.orb

/**
 * UI-layer state for the animated orb — decoupled from JarvisState so the
 * Canvas rendering layer has no dependency on the runtime layer.
 *
 * Amplitude (0f..1f) is inline on the two states that drive waveform height.
 * It is injected by OrbViewModel from SyntheticAmplitudeSource at ~30 fps.
 */
sealed class OrbVisualState {

    /** Service stopped or not yet started. Dim, barely visible. */
    object Dormant : OrbVisualState()

    /** Service running, wake-word loop active. Soft cyan breath. */
    object WakeListening : OrbVisualState()

    /** Wake phrase just detected. Brief white flash before pipeline opens. */
    object Activating : OrbVisualState()

    /** Mic open, waiting for user to speak. Green + waveform halo. */
    data class Listening(val amplitude: Float = 0f) : OrbVisualState()

    /** LLM inference in progress, or a tool is executing. Amber + rotation arc. */
    data class Processing(val toolName: String? = null) : OrbVisualState()

    /** TTS playing. Purple + energetic waveform. */
    data class Speaking(val amplitude: Float = 0f) : OrbVisualState()

    /** Barge-in detected mid-sentence. Red snap. */
    object Interrupted : OrbVisualState()

    /** User pressed Silence. Cyan fading out before returning to WakeListening. */
    object Silencing : OrbVisualState()

    /** Offline — cloud LLM unreachable. Slate grey, slow pulse. */
    object Degraded : OrbVisualState()

    /** Mic held by phone call or another app. Frozen, dark. */
    object MicBlocked : OrbVisualState()
}
