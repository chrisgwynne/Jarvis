package com.jarvis.assistant.context

import android.util.Log
import com.jarvis.assistant.voice.VoiceFeatureFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * AmbientContextSnapshot — the single value type that aggregates every
 * "what is the user / device doing right now" signal Jarvis cares about.
 *
 * Replaces the seven separate read calls scattered through JarvisRuntime
 * (Presence, DrivingModeManager, ContextEngine.isCharging, etc.) with one
 * cohesive read.  Consumers observe [AmbientContextAggregator.snapshot]
 * (a StateFlow) and react to changes; no one polls the underlying sources
 * directly from the hot path anymore.
 *
 * # Fields
 *
 * Each field is nullable when the source can't currently answer (e.g. no
 * location permission, no last-spoken time yet) so consumers can fall back
 * deterministically rather than guessing.
 */
data class AmbientContextSnapshot(
    /** Wall-clock ms at which this snapshot was assembled. */
    val timestampMs:            Long,
    /** True if the user is most likely driving (Driving manager or BT-in-car). */
    val isDriving:              Boolean,
    /** True if the phone is currently in a call (ringing or connected). */
    val isInCall:               Boolean,
    /** True if Jarvis itself is speaking via TTS. */
    val isJarvisSpeaking:       Boolean,
    /** True if Jarvis is in the listening / recognising state. */
    val isJarvisListening:      Boolean,
    /** Battery percent 0..100, or null when not yet known. */
    val batteryPercent:         Int?,
    /** True if the device is charging. */
    val isCharging:             Boolean,
    /** True if the screen is on. */
    val screenOn:               Boolean,
    /** Bluetooth headset / earbuds connected. */
    val isHeadsetConnected:     Boolean,
    /** True if any audio media is currently playing (Spotify / YouTube / etc.). */
    val isMediaPlaying:         Boolean,
    /** Foreground app package, or null when launcher/unknown. */
    val foregroundAppPackage:   String?,
    /** Online / network reachable. */
    val isOnline:               Boolean,
    /** Composed Presence (timePhase + activity + minutesSinceInteraction). */
    val presence:               Presence,
    /** Ms since the user last interacted, or [Long.MAX_VALUE] when unknown. */
    val msSinceLastInteraction: Long
)

/**
 * AmbientContextAggregator — single producer of [AmbientContextSnapshot].
 *
 * Owned by JarvisRuntime.  Pulls from existing signal sources without
 * mutating them — this class is purely read-side.
 *
 * Gated by [VoiceFeatureFlags.Flag.AMBIENT_CONTEXT_ENABLED] (default OFF).
 * When the flag is off, [start] is a no-op and [snapshot.value] returns a
 * conservative default — consumers can wire the field without committing
 * to its production rollout.
 *
 * Refresh model:
 *  - Background coroutine reads every [REFRESH_INTERVAL_MS] (default 5s).
 *  - Cheap: every underlying source is already a `@Volatile` field or a
 *    fast getter; no I/O.
 *  - The flow only emits when a field changed, so collectors don't get
 *    duplicate events.
 */
class AmbientContextAggregator(
    private val refresh: () -> AmbientContextSnapshot
) {

    companion object {
        private const val TAG = "AmbientContextAggregator"
        const val REFRESH_INTERVAL_MS: Long = 5_000L
    }

    private val _snapshot = MutableStateFlow(defaultSnapshot())
    val snapshot: StateFlow<AmbientContextSnapshot> = _snapshot.asStateFlow()

    private var pollJob: Job? = null

    /** Start polling.  Idempotent — repeat calls are no-ops. */
    fun start(scope: CoroutineScope) {
        if (!VoiceFeatureFlags.isEnabled(VoiceFeatureFlags.Flag.AMBIENT_CONTEXT_ENABLED)) {
            Log.d(TAG, "[AMBIENT_CONTEXT_DISABLED] flag off — aggregator idle")
            return
        }
        if (pollJob?.isActive == true) return

        pollJob = scope.launch(Dispatchers.Default) {
            Log.d(TAG, "[AMBIENT_CONTEXT_STARTED] interval=${REFRESH_INTERVAL_MS}ms")
            while (isActive) {
                try {
                    val next = refresh()
                    if (next != _snapshot.value) {
                        _snapshot.value = next
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[AMBIENT_CONTEXT_REFRESH_FAILED] ${e.message}")
                }
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        Log.d(TAG, "[AMBIENT_CONTEXT_STOPPED]")
    }

    /** Force an immediate refresh.  Useful when an event-driven source notifies. */
    fun refreshNow() {
        if (!VoiceFeatureFlags.isEnabled(VoiceFeatureFlags.Flag.AMBIENT_CONTEXT_ENABLED)) return
        try {
            val next = refresh()
            if (next != _snapshot.value) _snapshot.value = next
        } catch (e: Exception) {
            Log.w(TAG, "[AMBIENT_CONTEXT_REFRESH_FAILED] ${e.message}")
        }
    }

    private fun defaultSnapshot() = AmbientContextSnapshot(
        timestampMs            = System.currentTimeMillis(),
        isDriving              = false,
        isInCall               = false,
        isJarvisSpeaking       = false,
        isJarvisListening      = false,
        batteryPercent         = null,
        isCharging             = false,
        screenOn               = true,
        isHeadsetConnected     = false,
        isMediaPlaying         = false,
        foregroundAppPackage   = null,
        isOnline               = true,
        presence               = Presence(
            timePhase                 = TimePhase.DAY,
            activity                  = ActivityMode.IDLE,
            minutesSinceInteraction   = Long.MAX_VALUE
        ),
        msSinceLastInteraction = Long.MAX_VALUE
    )
}
