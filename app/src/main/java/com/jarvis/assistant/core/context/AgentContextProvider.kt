package com.jarvis.assistant.core.context

import android.util.Log
import com.jarvis.assistant.context.ContextEngine
import com.jarvis.assistant.context.Presence
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.core.events.EventBus
import com.jarvis.assistant.core.events.EventKind
import com.jarvis.assistant.proactive.ContextSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * AgentContextProvider — assembles an [AgentContext] on demand by merging
 * the device, presence, and proactive slices, AND keeps a live
 * [StateFlow] updated by subscribing to [EventBus] for presence-relevant
 * events.
 *
 * Three ways to read:
 *   • [current] — build fresh and update the StateFlow. Blocking-synchronous.
 *   • [latest]  — last built context; null until first build.
 *   • [attach]  — subscribes to the bus so any presence-relevant event
 *     (user utterance, driving mode, screen, power, foreground app,
 *     proximity) auto-rebuilds. The StateFlow is always fresh for
 *     consumers that want continuous awareness.
 */
class AgentContextProvider(
    private val contextEngine: ContextEngine,
    private val presenceProvider: () -> Presence,
    private val snapshotProvider: () -> ContextSnapshot? = { null },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _latest = MutableStateFlow<AgentContext?>(null)
    val latest: StateFlow<AgentContext?> = _latest

    private var job: Job? = null

    fun current(): AgentContext? {
        val snapshot = snapshotProvider() ?: return null
        val device = contextEngine.build()
        val presence = presenceProvider()
        val ctx = AgentContext(
            nowMs = System.currentTimeMillis(),
            device = device,
            presence = presence,
            proactive = snapshot,
        )
        _latest.value = ctx
        return ctx
    }

    /**
     * Subscribe to the bus and rebuild on any event whose kind is
     * relevant to presence or the snapshot. Debouncing is not needed at
     * the call rates we see; rebuild is cheap.
     */
    fun attach(flow: Flow<Event> = EventBus.events) {
        if (job != null) return
        job = scope.launch {
            try {
                flow.collect { e ->
                    if (e.kind in PRESENCE_RELEVANT) {
                        try { current() } catch (t: Throwable) {
                            Log.w(TAG, "rebuild failed: ${t.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "bus subscription failed: ${e.message}")
            }
        }
    }

    fun detach() {
        job?.cancel()
        job = null
    }

    companion object {
        private const val TAG = "AgentContextProvider"
        private val PRESENCE_RELEVANT: Set<EventKind> = setOf(
            EventKind.USER_UTTERANCE,
            EventKind.JARVIS_SPEAKING_STARTED,
            EventKind.JARVIS_SPEAKING_ENDED,
            EventKind.DRIVING_MODE_ON,
            EventKind.DRIVING_MODE_OFF,
            EventKind.SCREEN_ON,
            EventKind.SCREEN_OFF,
            EventKind.POWER_CONNECTED,
            EventKind.POWER_DISCONNECTED,
            EventKind.HEADSET_CONNECTED,
            EventKind.HEADSET_DISCONNECTED,
            EventKind.FOREGROUND_APP_CHANGED,
            EventKind.PROXIMITY_NEAR,
            EventKind.PROXIMITY_FAR,
            EventKind.PLACE_ARRIVED,
            EventKind.PLACE_LEFT,
            EventKind.NETWORK_AVAILABLE,
            EventKind.NETWORK_LOST,
        )
    }
}
