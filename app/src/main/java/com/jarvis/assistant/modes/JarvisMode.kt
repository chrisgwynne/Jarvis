package com.jarvis.assistant.modes

import android.util.Log
import com.jarvis.assistant.voice.VoiceFeatureFlags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * JarvisMode — coarse behaviour profile.  Each mode is a named bundle of
 * settings that other subsystems consult.
 *
 * Status: **scaffold only**.  Gated by [VoiceFeatureFlags.Flag.JARVIS_MODES_ENABLED]
 * (default OFF).  Until that flag flips, [ModeController.current] always
 * returns [NORMAL] — consumers can call it freely without behaviour change.
 */
enum class JarvisMode(
    val displayName: String,
    /** 0 (minimal) to 1 (verbose). */                       val verbosity: Float,
    /** 0 (insensitive) to 1 (hair-trigger). */              val wakeSensitivity: Float,
    /** Allowed to speak proactively without user prompt. */  val proactiveSpeechAllowed: Boolean,
    /** Minimum urgency threshold for proactive speech.   */  val proactiveUrgencyMin: Float,
    /** Require explicit confirmation for risky actions. */   val strictConfirmation: Boolean,
    /** Prefer ear / phone speaker / car output, etc.   */    val preferredOutput: AudioOutput
) {
    NORMAL ("Normal",
        verbosity = 0.6f, wakeSensitivity = 0.6f,
        proactiveSpeechAllowed = true,  proactiveUrgencyMin = 0.55f,
        strictConfirmation = false, preferredOutput = AudioOutput.DEFAULT),

    DRIVING("Driving",
        verbosity = 0.7f, wakeSensitivity = 0.7f,
        proactiveSpeechAllowed = true,  proactiveUrgencyMin = 0.40f,   // talk more in car
        strictConfirmation = true,  preferredOutput = AudioOutput.BLUETOOTH_OR_SPEAKER),

    WORK   ("Work",
        verbosity = 0.4f, wakeSensitivity = 0.5f,
        proactiveSpeechAllowed = false, proactiveUrgencyMin = 0.80f,
        strictConfirmation = false, preferredOutput = AudioOutput.NOTIFICATION_ONLY),

    NIGHT  ("Night",
        verbosity = 0.3f, wakeSensitivity = 0.4f,
        proactiveSpeechAllowed = false, proactiveUrgencyMin = 0.90f,
        strictConfirmation = true,  preferredOutput = AudioOutput.WHISPER),

    FOCUS  ("Focus",
        verbosity = 0.3f, wakeSensitivity = 0.4f,
        proactiveSpeechAllowed = false, proactiveUrgencyMin = 0.95f,
        strictConfirmation = false, preferredOutput = AudioOutput.NOTIFICATION_ONLY),

    FOOTBALL("Football",
        verbosity = 0.5f, wakeSensitivity = 0.7f,
        proactiveSpeechAllowed = true,  proactiveUrgencyMin = 0.50f,
        strictConfirmation = false, preferredOutput = AudioOutput.DEFAULT),

    AWAY   ("Away",
        verbosity = 0.5f, wakeSensitivity = 0.6f,
        proactiveSpeechAllowed = true,  proactiveUrgencyMin = 0.45f,    // home/security higher priority
        strictConfirmation = true,  preferredOutput = AudioOutput.NOTIFICATION_ONLY);

    enum class AudioOutput { DEFAULT, BLUETOOTH_OR_SPEAKER, NOTIFICATION_ONLY, WHISPER }
}

/**
 * ModeController — single point that owns the current [JarvisMode] and
 * publishes changes.  Other systems should observe [currentFlow] rather
 * than asking "what mode are we in?" on the hot path.
 */
class ModeController {

    companion object { private const val TAG = "ModeController" }

    private val _current = MutableStateFlow(JarvisMode.NORMAL)
    val currentFlow: StateFlow<JarvisMode> = _current.asStateFlow()

    val current: JarvisMode
        get() = if (flagOn()) _current.value else JarvisMode.NORMAL

    /** True iff the last [set] / [consumeContext] was a manual user override. */
    @Volatile private var lockedByUser: Boolean = false

    fun set(mode: JarvisMode, reason: String = "manual") {
        if (!flagOn()) return
        if (_current.value == mode) return
        Log.d(TAG, "[MODE_CHANGE] ${_current.value} → $mode (reason=$reason)")
        _current.value = mode
        lockedByUser = reason == "manual"
    }

    /** Allow auto-switching again after a manual override. */
    fun releaseLock() { lockedByUser = false }

    /**
     * B2 / Tier-B auto-switch logic.  Consumes an
     * [com.jarvis.assistant.context.AmbientContextSnapshot] and decides
     * whether the current mode should change.
     *
     * Decision tree (first match wins):
     *   1. user-locked (`set("manual")` was called)         → no change
     *   2. driving                                         → DRIVING
     *   3. night (22:00–07:00) AND screen off AND headset  → NIGHT
     *   4. night AND screen off                            → NIGHT
     *   5. headset connected AND media playing             → keep current
     *      (we don't auto-switch to FOCUS just because audio is playing)
     *   6. everything else                                 → NORMAL
     *
     * Idempotent — only emits when the resolved mode actually differs.
     */
    fun consumeContext(ctx: com.jarvis.assistant.context.AmbientContextSnapshot) {
        if (!flagOn()) return
        if (lockedByUser) return

        val isNightHour = ctx.presence.timePhase ==
            com.jarvis.assistant.context.TimePhase.NIGHT

        val resolved = when {
            ctx.isDriving                              -> JarvisMode.DRIVING
            isNightHour && !ctx.screenOn && ctx.isHeadsetConnected -> JarvisMode.NIGHT
            isNightHour && !ctx.screenOn               -> JarvisMode.NIGHT
            else                                       -> JarvisMode.NORMAL
        }

        if (resolved != _current.value) {
            Log.d(TAG, "[MODE_AUTO_SWITCH] ${_current.value} → $resolved " +
                "(driving=${ctx.isDriving} screen=${ctx.screenOn} " +
                "night=$isNightHour headset=${ctx.isHeadsetConnected})")
            _current.value = resolved
        }
    }

    private fun flagOn() =
        VoiceFeatureFlags.isEnabled(VoiceFeatureFlags.Flag.JARVIS_MODES_ENABLED)
}
