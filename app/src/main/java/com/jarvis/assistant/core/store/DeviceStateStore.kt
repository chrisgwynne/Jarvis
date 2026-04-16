package com.jarvis.assistant.core.store

import com.jarvis.assistant.core.state.JarvisState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * DeviceStateStore — observable snapshot of the entire Jarvis runtime.
 *
 * This is intentionally a Kotlin object (singleton) so the UI, service,
 * runtime, and tests all share a single consistent view.  Only JarvisRuntime
 * should write to it; everyone else reads.
 *
 * WHY NOT IN A VIEWMODEL?
 *   The service outlives the Activity.  A plain singleton backed by StateFlow
 *   is the simplest cross-lifecycle solution without introducing Hilt/Dagger.
 */
object DeviceStateStore {

    private val _state = MutableStateFlow(DeviceState())
    val state: StateFlow<DeviceState> = _state.asStateFlow()

    /** Current snapshot — safe to read from any thread. */
    val current: DeviceState get() = _state.value

    /** Atomically update any field(s) on the state. */
    fun update(transform: DeviceState.() -> DeviceState) = _state.update(transform)

    /** Reset to a clean slate (e.g. on service restart). */
    fun reset() {
        _state.value = DeviceState()
    }
}

/**
 * Immutable snapshot of the Jarvis runtime state at a point in time.
 * All fields have safe defaults so the store is always readable.
 */
data class DeviceState(
    // ── Runtime ───────────────────────────────────────────────────────────────
    val runtimeState: JarvisState = JarvisState.ServiceStopped,
    val wakeModeActive: Boolean = false,

    // ── Mic / Audio ───────────────────────────────────────────────────────────
    val micAvailable: Boolean = true,
    val ttsPlaying: Boolean = false,
    val interruptionInProgress: Boolean = false,
    val headsetConnected: Boolean = false,

    // ── Conversation ──────────────────────────────────────────────────────────
    val currentConversationId: String? = null,
    val lastUserUtterance: String = "",
    val lastAssistantResponse: String = "",

    // ── Tool execution ────────────────────────────────────────────────────────
    val currentToolName: String? = null,

    // ── Device ────────────────────────────────────────────────────────────────
    val batteryPercent: Int = -1,
    val isCharging: Boolean = false,
    val isOnline: Boolean = true,

    // ── Incoming call ─────────────────────────────────────────────────────────
    /** Non-null while Jarvis is handling an incoming ringing call. */
    val incomingCallInfo: com.jarvis.assistant.call.CallInfo? = null,
    /** Non-null while a call is connected (CallActive state). */
    val activeCallInfo: com.jarvis.assistant.call.CallInfo? = null,

    // ── Silence ───────────────────────────────────────────────────────────────
    val silenceModeActive: Boolean = false,

    // ── Diagnostics ───────────────────────────────────────────────────────────
    val lastSyncTime: Long = 0L,
    val lastStateChangeTime: Long = System.currentTimeMillis()
)
