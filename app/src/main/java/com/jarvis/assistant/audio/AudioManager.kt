package com.jarvis.assistant.audio

import android.content.Context
import android.util.Log
import com.jarvis.assistant.llm.LlmRouter
import com.jarvis.assistant.service.JarvisService
import com.jarvis.assistant.tools.ToolHandler
import com.jarvis.assistant.util.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * AudioManager — orchestrates the full voice pipeline.
 *
 * PIPELINE (runs end-to-end in a single coroutine per wake-word event):
 *
 *   WakeWordDetector detects "Jarvis"
 *        │
 *        ▼  release mic
 *      [chime plays ~350 ms]
 *        │
 *        ▼
 *   SpeechCapture.listen()    → transcript: String
 *        │
 *        ▼
 *   LlmRouter.complete()      → response: String
 *        │
 *        ▼
 *   TtsEngine.speak()         → audio out
 *        │
 *        ▼
 *   loop back to listen (persistent conversation)
 *
 * SILENCE / INTERRUPTION HANDLING:
 *   silence()    — cancels the active pipeline and restarts wake-word detection.
 *   Audio interruptions (phone calls, media) are detected by repeated fast
 *   failures from SpeechCapture (<3 s per attempt).  After 3 consecutive fast
 *   failures Jarvis returns to wake-word mode automatically.
 */
class AudioManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val onStateChange: (JarvisService.State) -> Unit,
    private val onLogEntry: (String) -> Unit
) {
    companion object {
        private const val TAG = "AudioManager"
        /** If SpeechCapture returns blank this quickly, treat it as an audio error (call in progress etc.) */
        private const val FAST_FAIL_MS = 3_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val speechCapture = SpeechCapture(context)
    private val ttsEngine     = TtsEngine(context)
    private val llmRouter     = LlmRouter(context)
    private val toolHandler   = ToolHandler(context, settings)

    private var wakeWordDetector: WakeWordDetector? = null

    /** The currently-running conversation pipeline coroutine — null when idle. */
    private var pipelineJob: Job? = null

    // ── Public API ────────────────────────────────────────────────────────────

    fun start() {
        ttsEngine.applyVoice(settings.ttsVoiceName)
        startWakeWordDetection()
    }

    /**
     * Skip the wake word — triggered by the UI mic button.
     * Stops any in-progress pipeline, then jumps straight to the conversation loop.
     */
    fun triggerManually() {
        wakeWordDetector?.stop()
        wakeWordDetector = null
        onWakeWordDetected()
    }

    /**
     * Silence: stop the active conversation loop and return to wake-word listening.
     * The service stays running — say "Jarvis" to re-activate.
     */
    fun silence() {
        pipelineJob?.cancel()
        pipelineJob = null
        speechCapture.cancel()
        backToWakeWord()
        Log.d(TAG, "Silenced — back to wake word")
    }

    /** Tear everything down cleanly. */
    fun stop() {
        pipelineJob?.cancel()
        pipelineJob = null
        wakeWordDetector?.stop()
        wakeWordDetector = null
        speechCapture.cancel()
        ttsEngine.shutdown()
        scope.cancel()
        Log.d(TAG, "Stopped")
    }

    // ── Wake-word loop ────────────────────────────────────────────────────────

    private fun startWakeWordDetection() {
        wakeWordDetector?.stop()
        val detector = GoogleWakeWordDetector(
            context    = context,
            onDetected = ::onWakeWordDetected,
            onError    = { msg ->
                onLogEntry(msg)
                onStateChange(JarvisService.State.IDLE)
            }
        )
        wakeWordDetector = detector
        detector.start()
        Log.d(TAG, "Wake-word detection started")
    }

    // ── Pipeline ──────────────────────────────────────────────────────────────

    /**
     * Called on the Main thread by WakeWordDetector when "Jarvis" is heard.
     *
     * Runs a PERSISTENT CONVERSATION LOOP — after every response Jarvis stays
     * in LISTENING and immediately waits for the next command. No wake word is
     * needed for follow-up questions.
     *
     * The loop exits (returning to wake-word detection) when:
     *   • The user says a stop command ("stop", "bye", "goodbye", "sleep", etc.)
     *   • Audio is repeatedly unavailable (phone call, media takeover)
     *   • silence() is called from the UI
     *   • An unrecoverable error occurs
     *   • The service is stopped (coroutine cancelled)
     */
    private fun onWakeWordDetected() {
        // Cancel any previous pipeline before launching a new one
        pipelineJob?.cancel()

        Log.d(TAG, "Wake word detected — entering persistent conversation loop")

        pipelineJob = scope.launch {
            try {
                // ── Activation ──────────────────────────────────────────────
                onStateChange(JarvisService.State.LISTENING)
                onLogEntry("● Jarvis activated")

                wakeWordDetector?.stop()
                wakeWordDetector = null

                ttsEngine.playChime()   // ~350 ms

                // ── Persistent conversation loop ─────────────────────────────
                var consecutiveFastFails = 0

                while (currentCoroutineContext().isActive) {

                    // 1. Listen for next command
                    onStateChange(JarvisService.State.LISTENING)
                    onLogEntry("Listening…")

                    val listenStart  = System.currentTimeMillis()
                    val transcript   = speechCapture.listen()
                    val listenMillis = System.currentTimeMillis() - listenStart

                    if (transcript.isBlank()) {
                        if (listenMillis < FAST_FAIL_MS) {
                            // Recogniser failed almost instantly → probably a phone call
                            // or media app holding the mic.  Back off and count failures.
                            consecutiveFastFails++
                            if (consecutiveFastFails >= 3) {
                                onLogEntry("Audio unavailable — returning to wake word mode")
                                consecutiveFastFails = 0
                                backToWakeWord()
                                return@launch
                            }
                            delay(1_000)
                        } else {
                            consecutiveFastFails = 0
                            delay(200)
                        }
                        continue
                    }

                    consecutiveFastFails = 0
                    onLogEntry("You: $transcript")

                    // 2. Stop command → exit conversation
                    if (isStopCommand(transcript)) {
                        val bye = "Okay, going quiet. Say Jarvis to wake me."
                        onLogEntry("Jarvis: $bye")
                        onStateChange(JarvisService.State.SPEAKING)
                        if (settings.voiceResponse) ttsEngine.speak(bye)
                        backToWakeWord()
                        return@launch
                    }

                    // 3. Tool detection (call / text / WhatsApp / open app / device controls / search)
                    onStateChange(JarvisService.State.PROCESSING)
                    val toolResult = toolHandler.handle(transcript)

                    if (toolResult is ToolHandler.Result.Executed) {
                        onLogEntry("Jarvis: ${toolResult.feedback}")
                        onStateChange(JarvisService.State.SPEAKING)
                        if (settings.voiceResponse) ttsEngine.speak(toolResult.feedback)
                        continue   // stay in conversation loop
                    }

                    val effectiveTranscript = when (toolResult) {
                        is ToolHandler.Result.Augmented -> {
                            onLogEntry("Searching…")
                            toolResult.newTranscript
                        }
                        else -> transcript
                    }

                    // 4. LLM inference
                    onLogEntry("Thinking…")
                    val response = llmRouter.complete(effectiveTranscript)
                    onLogEntry("Jarvis: $response")

                    // 5. Speak — then loop straight back to LISTENING
                    onStateChange(JarvisService.State.SPEAKING)
                    if (settings.voiceResponse) ttsEngine.speak(response)

                    // loop continues → no wake word needed for next question
                }

            } catch (e: CancellationException) {
                throw e   // service stopped or silence() called — do not restart
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline error", e)
                onLogEntry("Something went wrong.")
                backToWakeWord()
            }
        }
    }

    /** Words/phrases that end the conversation and return to wake-word mode. */
    private fun isStopCommand(transcript: String): Boolean {
        val t = transcript.lowercase().trim()
        return t == "stop"       || t == "bye"           || t == "goodbye"   ||
               t == "sleep"      || t == "go to sleep"   || t == "good night" ||
               t == "that's all" || t == "that's enough" || t == "cancel"    ||
               t.startsWith("stop listening") || t.startsWith("goodbye jarvis")
    }

    /** Return to IDLE and restart the wake-word detector. */
    private fun backToWakeWord() {
        onStateChange(JarvisService.State.IDLE)
        startWakeWordDetection()
    }
}
