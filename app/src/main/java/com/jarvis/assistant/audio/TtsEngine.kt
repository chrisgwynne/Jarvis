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

        suspendCancellableCoroutine { cont ->
            val utteranceId = "jarvis_${System.currentTimeMillis()}"

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onDone(id: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onError(id: String?) {
                    Log.w(TAG, "TTS utterance error for $id")
                    if (cont.isActive) cont.resume(Unit)
                }
                @Suppress("DEPRECATION")
                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onStart(id: String?) {}
            })

            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            cont.invokeOnCancellation { tts.stop() }
        }
    }

    /**
     * Play a short ascending two-tone chime to signal that Jarvis heard the wake word.
     * Suspends for ~600 ms before returning.
     */
    suspend fun playChime() {
        val chime = buildChime() ?: return
        chime.play()
        delay(600L)
        chime.release()
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
