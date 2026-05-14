package com.jarvis.assistant.voice.stt

import com.jarvis.assistant.voice.VoiceFeatureFlags
import kotlinx.coroutines.flow.Flow

/**
 * SpeechToTextProvider — pluggable STT backend interface.
 *
 * The current implementation [AndroidSpeechRecognizerProvider] wraps the
 * existing [com.jarvis.assistant.audio.SpeechCapture] for zero-risk
 * parity with today's behaviour.  A future
 * [RemoteStreamingWhisperProvider] streams PCM over Tailscale to the
 * Linux box for higher-accuracy dictation, gated by
 * [VoiceFeatureFlags.Flag.REMOTE_WHISPER_STT_ENABLED].
 *
 * Providers return a [Flow] of [Transcript] events so streaming partials
 * can drive early intent detection without waiting for the final.
 */
interface SpeechToTextProvider {

    enum class Kind { ANDROID_BUILTIN, REMOTE_WHISPER, HYBRID }

    /** Stable identifier for logs and provider selection. */
    val kind: Kind

    /**
     * Begin capturing.  Emits [Transcript.Partial] events as recognition
     * progresses and a single terminal [Transcript.Final] (or
     * [Transcript.Failed]) at the end of the utterance.
     *
     * Cancelling the collecting coroutine cancels the capture.
     */
    fun listen(forceOffline: Boolean = false): Flow<Transcript>

    /** Tear down provider-owned resources.  Safe to call multiple times. */
    fun release()

    sealed class Transcript {
        /** Best-guess text the recogniser has so far; may be overwritten. */
        data class Partial(val text: String, val confidence: Float = 0f) : Transcript()
        /** Final N-best list ordered by recogniser confidence. */
        data class Final(
            val candidates: List<String>,
            val confidences: FloatArray? = null
        ) : Transcript()
        /** Capture failed before reaching a final result. */
        data class Failed(val code: Int, val message: String) : Transcript()
    }
}

/**
 * SttProviderSelector — chooses the active provider based on feature
 * flags + network availability.  Designed to be cheap to call on every
 * utterance so settings changes pick up without a process restart.
 */
class SttProviderSelector(
    private val android: SpeechToTextProvider,
    private val remote:  SpeechToTextProvider?
) {
    fun select(isOnline: Boolean): SpeechToTextProvider {
        val whisperOn = VoiceFeatureFlags.isEnabled(
            VoiceFeatureFlags.Flag.REMOTE_WHISPER_STT_ENABLED
        )
        val streamingOn = VoiceFeatureFlags.isEnabled(
            VoiceFeatureFlags.Flag.VOICE_STREAMING_STT_ENABLED
        )
        return when {
            whisperOn && streamingOn && remote != null && isOnline -> remote
            else                                                   -> android
        }
    }
}
