package com.jarvis.assistant.runtime.controllers

import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.audio.TtsEngine
import com.jarvis.assistant.proactive.ProactiveAction
import com.jarvis.assistant.proactive.ProactiveEventType
import com.jarvis.assistant.proactive.settings.ProactivityGate
import com.jarvis.assistant.util.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * TtsResponseController — the diagnostics-and-sample TTS surface.
 *
 * **Scope (this PR):**
 *   - `testSpeak(voiceName)` — voice-audition sample, used by Settings.
 *   - `speakProactivityTest(text, onResult)` — "Test spoken message"
 *     button.  Bypasses the Proactivity gate by design.
 *   - `dispatchProactivityGateTest(onResult)` — gate verdict reporter.
 *
 * **Out of scope (still on JarvisRuntime):**
 *   - the main pipeline TTS path (`streamAndSpeak`, `speakAndRecord`)
 *   - location-reminder + reminder-triggered TTS (couples to wake
 *     detector + audio focus + state machine; deferred to a future
 *     `SpeechSessionController` extraction).
 *
 * The controller takes its dependencies as constructor args + lambdas
 * (rather than holding a reference to `JarvisRuntime`) so it stays
 * pure-ish and JVM-testable.  See
 * `docs/architecture/runtime-split.md` for the broader plan.
 */
class TtsResponseController(
    private val ttsEngine: TtsEngine,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    /** Apply a new voice profile to the TTS engine before sampling. */
    private val applyVoice: (String) -> Unit,
    /** Suppress wake-word detection for the duration of the utterance. */
    private val suppressWake: () -> Unit,
    /** Restore wake-word detection after the utterance. */
    private val restoreWake: () -> Unit,
) {

    companion object { private const val TAG = "TtsResponseController" }

    /**
     * Switch to [voiceName] and speak a short test phrase so the user
     * can audition it.  Wake detection is suppressed for the duration
     * so the sample audio doesn't trigger the pipeline.
     */
    fun testSpeak(voiceName: String) {
        applyVoice(voiceName)
        scope.launch {
            suppressWake()
            try {
                ttsEngine.speak("Hi, I'm Jarvis. This is how I sound.")
            } finally {
                restoreWake()
            }
        }
    }

    /**
     * Proactivity diagnostics — speak a fixed sample line *immediately*
     * and bypass every Proactivity gate (master switch / quiet hours /
     * interruption mode / category / cooldown).
     *
     * Production behaviour of the Settings "Test spoken message now"
     * button.  Funnelling this through the gate would be hostile —
     * the whole point is to prove TTS is alive even when something
     * upstream would have muted normal proactive output.
     *
     * [onResult] receives `null` on success or a short human-friendly
     * failure reason.  Fired on an arbitrary dispatcher; the UI bounces
     * to main as needed.
     *
     * Log markers:
     *   [PROACTIVITY_TEST_SPEAK_REQUESTED]
     *   [PROACTIVITY_TEST_SPEAK_BYPASS_GATE]
     *   [PROACTIVITY_TEST_SPEAK_SUCCESS]
     *   [PROACTIVITY_TEST_SPEAK_FAILED] reason=...
     */
    fun speakProactivityTest(
        text: String = "Proactivity test — if you can hear this, voice output is working.",
        onResult: (failureReason: String?) -> Unit = {},
    ) {
        Log.d(TAG, "[PROACTIVITY_TEST_SPEAK_REQUESTED] text=\"$text\"")
        Log.d(TAG, "[PROACTIVITY_TEST_SPEAK_BYPASS_GATE] reason=user_diagnostics_button")
        scope.launch {
            try {
                if (!settings.voiceResponse) {
                    val reason = "Voice response is disabled in Settings — turn it on first."
                    Log.w(TAG, "[PROACTIVITY_TEST_SPEAK_FAILED] reason=voice_disabled")
                    onResult(reason); return@launch
                }
                suppressWake()
                try {
                    ttsEngine.speak(text)
                    Log.d(TAG, "[PROACTIVITY_TEST_SPEAK_SUCCESS]")
                    onResult(null)
                } finally {
                    restoreWake()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "[PROACTIVITY_TEST_SPEAK_FAILED] reason=exception:${e.message}", e)
                onResult("TTS error — ${e.message ?: e::class.simpleName}")
            }
        }
    }

    /**
     * Dispatch a synthetic [ProactiveAction.SpeakAction] through the
     * live [ProactivityGate] and report back what the gate decided.
     * Used by the Settings "Test normal proactivity decision" button so
     * the user can see *which* gate (master / quiet / mode / cooldown)
     * is suppressing real proactive output right now.
     */
    fun dispatchProactivityGateTest(onResult: (String) -> Unit) {
        scope.launch {
            try {
                val action = ProactiveAction.SpeakAction(
                    text       = "Proactivity gate test.",
                    dedupeKey  = "diagnostics:gate_test:${System.currentTimeMillis()}",
                    sourceType = ProactiveEventType.BEHAVIORAL_LEARNING,
                )
                val gate = ProactivityGate(
                    settingsProvider = { JarvisApp.proactivitySettings.snapshot() },
                )
                val verdict = gate.evaluate(
                    action,
                    lastUserInteractionMs = System.currentTimeMillis(),
                )
                val human = when (verdict) {
                    is ProactivityGate.Verdict.Allow ->
                        "Would speak now (gate=Allow)."
                    is ProactivityGate.Verdict.Downgrade ->
                        "Would notify, not speak (gate=Downgrade, reason=${verdict.reason})."
                    is ProactivityGate.Verdict.Suppress ->
                        "Suppressed entirely (reason=${verdict.reason})."
                }
                Log.d(TAG, "[PROACTIVITY_TEST_GATE_VERDICT] $human")
                onResult(human)
            } catch (e: Throwable) {
                Log.e(TAG, "Gate test failed", e)
                onResult("Gate test failed: ${e.message ?: e::class.simpleName}")
            }
        }
    }
}
