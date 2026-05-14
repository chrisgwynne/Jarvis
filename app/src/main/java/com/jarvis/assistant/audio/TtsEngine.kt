package com.jarvis.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.sin

/**
 * TtsEngine — wraps Android TextToSpeech and the startup chime as suspend functions.
 *
 * SUSPEND SPEAK:
 *   speak() suspends the coroutine until the utterance is fully spoken,
 *   then resumes. If the coroutine is cancelled mid-speech, TTS is stopped.
 *
 * CHIME:
 *   playChime() generates a two-tone ascending sine-wave "ding" (A5→D6)
 *   entirely in memory — no audio file asset needed.
 */
class TtsEngine(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TtsEngine"
    }

    private val appContext = context.applicationContext
    private val tts = TextToSpeech(appContext, this)
    private val ready = AtomicBoolean(false)

    /**
     * The most recent text passed to [speak].  Read by
     * [com.jarvis.assistant.voice.attention.AttentionGate] as the echo-guard
     * reference — if the next captured transcript closely resembles this
     * text, it's our own TTS bleeding back into the microphone.
     *
     * Cleared once the utterance finishes so the guard does not keep
     * suppressing legitimate follow-ups indefinitely.
     */
    @Volatile var lastSpokenText: String? = null
        private set

    /**
     * Persists the voice name across the async [onInit] gap so that a voice
     * selected before the engine is ready is still applied once it fires.
     */
    @Volatile private var pendingVoiceName: String = ""

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "US English TTS language not available — falling back to device default")
                tts.language = Locale.getDefault()
            }
            ready.set(true)
            Log.d(TAG, "TTS engine ready")
            if (pendingVoiceName.isNotBlank()) {
                Log.d(TAG, "Applying deferred voice: $pendingVoiceName")
                applyVoice(pendingVoiceName)
            }
        } else {
            Log.e(TAG, "TTS init failed with status $status")
        }
    }

    /** Speak [text] aloud and suspend until playback is complete. */
    suspend fun speak(text: String) {
        if (!ready.get() || text.isBlank()) return

        lastSpokenText = text
        suspendCancellableCoroutine { cont ->
            val utteranceId = "jarvis_${System.currentTimeMillis()}"

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                // Clear the listener once the utterance is settled so the TTS native
                // connection doesn't keep a reference to this object (and through it, the
                // suspended continuation) after the coroutine has already resumed.
                private fun finish(resume: Boolean) {
                    tts.setOnUtteranceProgressListener(null)
                    if (resume && cont.isActive) cont.resume(Unit)
                }
                override fun onDone(id: String?)  = finish(true)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(true)
                override fun onStart(id: String?) {}
                override fun onError(id: String?) {
                    Log.w(TAG, "TTS utterance error for $id (no error code)")
                    finish(true)
                }
                @Suppress("DEPRECATION")
                override fun onError(utteranceId: String?, errorCode: Int) {
                    val reason = when (errorCode) {
                        TextToSpeech.ERROR_SYNTHESIS         -> "ERROR_SYNTHESIS"
                        TextToSpeech.ERROR_SERVICE           -> "ERROR_SERVICE"
                        TextToSpeech.ERROR_OUTPUT            -> "ERROR_OUTPUT"
                        TextToSpeech.ERROR_NETWORK           -> "ERROR_NETWORK"
                        TextToSpeech.ERROR_NETWORK_TIMEOUT   -> "ERROR_NETWORK_TIMEOUT"
                        TextToSpeech.ERROR_INVALID_REQUEST   -> "ERROR_INVALID_REQUEST"
                        TextToSpeech.ERROR_NOT_INSTALLED_YET -> "ERROR_NOT_INSTALLED_YET"
                        else                                 -> "unknown ($errorCode)"
                    }
                    Log.w(TAG, "TTS utterance error for $utteranceId — $reason")
                    finish(true)
                }
            })

            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            cont.invokeOnCancellation {
                tts.stop()
                tts.setOnUtteranceProgressListener(null)
            }
        }
    }

    /**
     * Play a short ascending two-tone chime to signal that Jarvis heard the wake word.
     * Suspends for ~600 ms before returning.
     *
     * Prefer [startChimeAsync] in the wake pipeline — it fires immediately and
     * returns so the microphone opens in parallel instead of waiting.
     */
    suspend fun playChime() {
        val chime = buildChime() ?: return
        try {
            chime.play()
            delay(600L)
        } finally {
            // Always release the AudioTrack — delay() throws on coroutine
            // cancellation, which would otherwise leave the hardware claim open.
            try { chime.release() } catch (_: Exception) {}
        }
    }

    /**
     * Fire-and-forget chime: starts playback and returns immediately.
     *
     * The chime is 2 × 150 ms = 300 ms of audio.  A daemon thread waits 350 ms
     * (a little longer than the audio) and then releases the AudioTrack.  Callers
     * do not block — the microphone opens in parallel while the chime plays.
     *
     * Use this in the wake pipeline instead of the suspending [playChime].
     */
    fun startChimeAsync() {
        val chime = buildChime() ?: return
        val t0 = android.os.SystemClock.elapsedRealtime()
        Log.d(TAG, "Chime start (async) t=+0ms")
        chime.play()
        Thread {
            Thread.sleep(350L)   // slightly longer than 300 ms audio to ensure full playback
            try { chime.release() } catch (_: Exception) {}
            Log.d(TAG, "Chime released after ${android.os.SystemClock.elapsedRealtime() - t0}ms")
        }.also { it.isDaemon = true; it.name = "jarvis-chime-release" }.start()
    }

    /**
     * Switch to an Android TTS device voice by name.
     * Pass blank to revert to the system default.
     */
    fun applyVoice(voiceName: String) {
        pendingVoiceName = voiceName
        if (voiceName.isBlank() || !ready.get()) {
            Log.d(TAG, "TTS engine not ready yet — voice '$voiceName' will be applied in onInit()")
            return
        }
        val voice = tts.voices?.find { it.name == voiceName }
        if (voice != null) {
            tts.voice = voice
            Log.d(TAG, "Voice set to: ${voice.name}")
        } else {
            Log.w(TAG, "Voice '$voiceName' not found on this device")
        }
    }

    /**
     * Immediately stop any current or queued speech without shutting down the engine.
     * Called by BargeInDetector when the user starts speaking during TTS playback.
     */
    fun stopSpeaking() {
        tts.stop()
    }

    /** Release all TTS resources. Call when the service is being destroyed. */
    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    // ── Chime generation ──────────────────────────────────────────────────────

    private fun buildChime(): AudioTrack? {
        return try {
            val sampleRate     = 44100
            val msPerTone      = 150
            val samplesPerTone = sampleRate * msPerTone / 1000
            val frequencies    = intArrayOf(880, 1174)
            val totalSamples   = samplesPerTone * frequencies.size
            val buffer         = ShortArray(totalSamples)

            for ((idx, freq) in frequencies.withIndex()) {
                val offset = idx * samplesPerTone
                for (i in 0 until samplesPerTone) {
                    val t            = i.toDouble() / sampleRate
                    val attackSamples = sampleRate * 5 / 1000
                    val envelope     = when {
                        i < attackSamples -> i.toDouble() / attackSamples
                        else              -> Math.exp(-3.5 * (i - attackSamples).toDouble() / samplesPerTone)
                    }
                    val sample = (sin(2.0 * PI * freq * t) * 32767.0 * 0.4 * envelope).toInt()
                    buffer[offset + i] = sample.coerceIn(-32768, 32767).toShort()
                }
            }

            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(totalSamples * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
                .also { track -> track.write(buffer, 0, buffer.size) }
        } catch (e: Exception) {
            Log.w(TAG, "Chime build failed: ${e.message}")
            null
        }
    }
}
