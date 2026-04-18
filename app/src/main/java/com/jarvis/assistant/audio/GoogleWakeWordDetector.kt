package com.jarvis.assistant.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * GoogleWakeWordDetector — listens continuously for the wake word using Android's built-in
 * SpeechRecognizer (Google STT). Zero additional API keys or dependencies.
 *
 * HOW IT WORKS:
 *   Runs a tight loop: start SpeechRecognizer → wait for result → check whether
 *   the transcript contains "jarvis" → if yes, fire onDetected and stop; if no,
 *   restart immediately. Partial results are also checked so activation fires as
 *   soon as "jarvis" appears mid-utterance, before the user stops speaking.
 *
 * TRADE-OFFS vs offline engine (TFLiteWakeWordDetector):
 *   + Free — no extra API key or model asset
 *   + No native library / extra APK weight
 *   − Requires internet (unless device has an on-device Google speech model)
 *   − ~100–500 ms gap while SpeechRecognizer restarts between sessions
 *   − Slightly higher battery than a dedicated DSP wake-word chip
 *
 * THREADING:
 *   SpeechRecognizer must be created and used on the Main thread. This class
 *   runs its loop on Dispatchers.Main. onDetected is always called on Main.
 */
class GoogleWakeWordDetector(
    private val context: Context,
    private val onDetected: () -> Unit,
    private val onError: (String) -> Unit = {}
) : WakeWordDetector {

    companion object {
        private const val TAG = "GoogleWakeWordDetector"
        private const val WAKE_WORD = "jarvis"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun start() {
        if (job?.isActive == true) return
        Log.d(TAG, "Starting wake-word detection loop")
        job = scope.launch { runDetectionLoop() }
    }

    override fun stop() {
        Log.d(TAG, "Stopping wake-word detection")
        job?.cancel()
        job = null
    }

    // ── Detection loop ────────────────────────────────────────────────────────

    private suspend fun runDetectionLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                val detected = withTimeoutOrNull(45_000L) { listenOnce() } ?: run {
                    Log.w(TAG, "listenOnce timed out — restarting detector")
                    false
                }
                if (detected) {
                    Log.d(TAG, "Wake word confirmed — firing callback")
                    onDetected()
                    return
                }
                delay(100)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Recognition error, retrying: ${e.message}")
                delay(2_000)
            }
        }
    }

    private suspend fun listenOnce(): Boolean = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        var recognizer: SpeechRecognizer? = null
        var settled = false

        fun settle(value: Boolean) {
            if (settled) return
            settled = true
            recognizer?.destroy()
            recognizer = null
            if (cont.isActive) cont.resume(value)
        }

        val listener = object : RecognitionListener {
            override fun onPartialResults(partial: Bundle?) {
                val candidates = partial
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?: return
                val hit = candidates.firstOrNull { it.contains(WAKE_WORD, ignoreCase = true) }
                if (hit != null) {
                    Log.d(TAG, "Partial wake-word hit: \"$hit\"")
                    settle(true)
                }
            }

            override fun onResults(results: Bundle?) {
                val candidates = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?: emptyList<String>()
                Log.d(TAG, "Final results: $candidates")
                settle(candidates.any { it.contains(WAKE_WORD, ignoreCase = true) })
            }

            override fun onError(error: Int) {
                Log.d(TAG, "Recognizer onError code=$error")
                settle(false)
            }

            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}
        }

        handler.post {
            if (!cont.isActive) return@post
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer!!.setRecognitionListener(listener)
            recognizer!!.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 0L)
                }
            )
        }

        cont.invokeOnCancellation {
            handler.post {
                recognizer?.stopListening()
                recognizer?.destroy()
                recognizer = null
            }
        }
    }
}
