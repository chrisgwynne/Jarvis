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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * SpeechCapture — wraps Android's built-in SpeechRecognizer as a suspend function.
 *
 * WHY ANDROID'S BUILT-IN RECOGNISER?
 *   No API key, no network cost (can use on-device model on Android 13+), and
 *   it has built-in VAD (Voice Activity Detection) that handles end-of-speech
 *   detection reliably — better than a DIY energy-threshold VAD for most phones.
 *
 * IMPORTANT THREADING CONSTRAINT:
 *   SpeechRecognizer MUST be created, used, and destroyed on the MAIN thread.
 *   We enforce this by always posting via Handler(Looper.getMainLooper()).
 *
 * MICROPHONE HANDOFF:
 *   WakeWordDetector must have released AudioRecord BEFORE listen() is called,
 *   because SpeechRecognizer opens its own mic session.
 *
 * ERROR HANDLING:
 *   ERROR_NO_MATCH (7) and ERROR_SPEECH_TIMEOUT (6) = user spoke but no result
 *   or didn't speak at all → we return "" so the pipeline retries gracefully.
 *   Other errors (hardware, recognizer crash) → return "" with a log warning.
 */
class SpeechCapture(private val context: Context) {

    companion object {
        private const val TAG = "SpeechCapture"
    }

    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Listen for one utterance and return the transcript.
     *
     * [onReady] is invoked on the main thread the moment the SpeechRecognizer
     * reports it is ready to receive audio (onReadyForSpeech).  Use this to
     * start any concurrent AudioRecord AFTER the recognizer has claimed the
     * microphone — starting an AudioRecord before that point can preempt the
     * recognition service on some devices.
     *
     * Returns "" if nothing was heard, recognition failed, or the 30 s hard
     * timeout fires.  Cancellation-safe.
     */
    suspend fun listen(onReady: (() -> Unit)? = null): String = withTimeoutOrNull(30_000L) {
        listenInternal(onReady)
    } ?: run {
        Log.w(TAG, "SpeechCapture timed out after 30 s — returning empty")
        cancel()
        ""
    }

    private suspend fun listenInternal(onReady: (() -> Unit)? = null): String = suspendCancellableCoroutine { cont ->

        // Register cancellation cleanup BEFORE posting to handler,
        // so if the coroutine is cancelled while the handler post is pending
        // we still clean up correctly.
        cont.invokeOnCancellation {
            mainHandler.post {
                recognizer?.cancel()
                recognizer?.destroy()
                recognizer = null
                Log.d(TAG, "Cancelled")
            }
        }

        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.w(TAG, "SpeechRecognizer not available on this device")
                if (cont.isActive) cont.resume("")
                return@post
            }

            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)

            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    Log.d(TAG, "Result: \"$text\"")
                    cleanup()
                    if (cont.isActive) cont.resume(text)
                }

                override fun onError(error: Int) {
                    val isSilence = error == SpeechRecognizer.ERROR_NO_MATCH ||
                                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                                    error == SpeechRecognizer.ERROR_CLIENT
                    Log.w(TAG, "Recognition error $error (silence=$isSilence)")
                    cleanup()
                    // Return empty string for silence; caller will restart wake-word loop
                    if (cont.isActive) cont.resume("")
                }

                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "Ready for speech")
                    // SpeechRecognizer has the mic — safe to start any concurrent AudioRecord now.
                    onReady?.invoke()
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { Log.d(TAG, "End of speech") }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Prefer on-device processing when possible (Android 13+)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

                // End-of-speech silence detection.
                // COMPLETE: 2 s — recogniser finalises after 2 s of silence.
                //   Reduced from 3 s; saves ~1 s of dead air on every turn with no
                //   audible difference for natural speech.
                // POSSIBLY_COMPLETE: 1 s — early partial-result trigger.
                //   Reduced from 1.5 s; snappier response on short commands.
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    2_000L
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    1_000L
                )
                // Minimum utterance length: 0 = no filtering, capture even brief commands
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                    0L
                )
            }

            recognizer?.startListening(intent)
            Log.d(TAG, "Listening…")
        }
    }

    /** Cancel any in-progress recognition. */
    fun cancel() {
        mainHandler.post {
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
        }
    }

    private fun cleanup() {
        recognizer?.destroy()
        recognizer = null
    }
}
