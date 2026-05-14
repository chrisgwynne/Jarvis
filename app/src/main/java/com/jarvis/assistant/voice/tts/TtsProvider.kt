package com.jarvis.assistant.voice.tts

import com.jarvis.assistant.voice.VoiceFeatureFlags

/**
 * TtsProvider — pluggable text-to-speech backend.
 *
 * The current production sink is [com.jarvis.assistant.audio.TtsEngine]
 * (Android's built-in TTS).  This interface exists so a future
 * [LocalOnDeviceTtsProvider] (Piper / Kokoro ONNX) or
 * [RemoteStreamingTtsProvider] (XTTS / a custom server over Tailscale)
 * can be slotted in without touching every TTS caller.
 *
 * Gated by [VoiceFeatureFlags.Flag.STREAMING_TTS_ENABLED] (default OFF).
 * The selector falls back to the Android provider for parity until the
 * flag flips.
 */
interface TtsProvider {

    enum class Kind { ANDROID_BUILTIN, LOCAL_ONDEVICE, REMOTE_STREAMING }

    val kind: Kind

    /** True once the backend has loaded its voices / connections. */
    fun isReady(): Boolean

    /**
     * Suspend until the utterance has been fully spoken.  Cancelling the
     * coroutine MUST stop playback immediately — the existing barge-in
     * path relies on `tts.stop()` reaching the speaker in < 150 ms.
     */
    suspend fun speak(text: String)

    /**
     * Optional streaming entry point.  Implementations that don't support
     * sentence-chunked playback can fall back to buffering into [speak].
     * The current Android backend treats each sentence as a separate
     * utterance which the runtime already provides.
     */
    suspend fun speakChunk(text: String) = speak(text)

    /** Stop any current / queued speech.  Idempotent. */
    fun stop()

    /** Release backend resources. */
    fun release()
}

/**
 * TtsProviderSelector — chooses the active provider per utterance.
 *
 * Cheap to call on every request so settings changes pick up without a
 * process restart.  Falls back to [androidBuiltIn] whenever the streaming
 * provider isn't configured or its readiness check fails.
 */
class TtsProviderSelector(
    private val androidBuiltIn: TtsProvider,
    private val localOnDevice:  TtsProvider? = null,
    private val remoteStreaming: TtsProvider? = null
) {
    fun select(): TtsProvider {
        val streamingOn = VoiceFeatureFlags.isEnabled(
            VoiceFeatureFlags.Flag.STREAMING_TTS_ENABLED
        )
        if (!streamingOn) return androidBuiltIn

        // Prefer local on-device → remote → Android, when ready.
        localOnDevice?.takeIf { it.isReady() }?.let  { return it }
        remoteStreaming?.takeIf { it.isReady() }?.let { return it }
        return androidBuiltIn
    }
}
