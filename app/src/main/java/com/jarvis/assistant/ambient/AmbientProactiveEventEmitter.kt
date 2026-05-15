package com.jarvis.assistant.ambient

import android.util.Log
import com.jarvis.assistant.ambient.db.AmbientEventDao
import com.jarvis.assistant.ambient.db.RoutinePatternDao
import com.jarvis.assistant.core.events.EventBus
import com.jarvis.assistant.core.events.EventKind
import com.jarvis.assistant.location.LocationTransition
import com.jarvis.assistant.location.PlaceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * AmbientProactiveEventEmitter — the top-level coordinator for Ambient
 * Intelligence.
 *
 * Pipeline:
 *  [EventBus] → [AmbientEventStore] → [RoutineLearningEngine] → [currentContext]
 *
 * Ambient triggers ([com.jarvis.assistant.core.decisions.triggers.Ambient*])
 * read [currentContext] via [com.jarvis.assistant.core.context.AgentContext.ambient]
 * on every proactive tick.  The emitter never calls TTS or posts notifications
 * directly — that is [com.jarvis.assistant.proactive.ProactiveEngine]'s job.
 *
 * Thread safety: [currentContext] is @Volatile.  Writers are the EventBus
 * collector (IO) and Todoist/HA refresh coroutines. Readers are the proactive
 * tick (Default).
 */
class AmbientProactiveEventEmitter(
    private val eventStore: AmbientEventStore,
    private val learningEngine: RoutineLearningEngine,
    private val settingsProvider: () -> AmbientSettings = { AmbientSettings() },
) {
    @Volatile
    var currentContext: AmbientContext = AmbientContext.EMPTY
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var contextRefreshJob: Job? = null

    companion object {
        private const val TAG = "AmbientEmitter"
        private const val CONTEXT_REFRESH_MS = 5 * 60_000L

        private val ETSY_PKG = "com.etsy.android"
        private val ETSY_OPEN_WINDOW_MS = 10 * 60_000L
        private val CAR_BT_WINDOW_MS = 30 * 60_000L
    }

    fun start() {
        Log.d(TAG, "[AMBIENT_EMITTER_START]")
        eventStore.attach()
        learningEngine.start()

        // Subscribe to EventBus to update context in real-time for
        // time-sensitive signals (car BT, location bucket).
        scope.launch(Dispatchers.IO) {
            EventBus.events.collect { event ->
                if (!settingsProvider().enabled) return@collect
                updateContextFromEvent(event)
            }
        }

        // Periodic refresh to pick up Todoist / HA state changes and
        // refresh routine patterns from the learning engine.
        contextRefreshJob = scope.launch(Dispatchers.IO) {
            while (true) {
                refreshContext()
                kotlinx.coroutines.delay(CONTEXT_REFRESH_MS)
            }
        }
    }

    fun stop() {
        contextRefreshJob?.cancel()
        Log.d(TAG, "[AMBIENT_EMITTER_STOP]")
    }

    /** Called after a proactive event was dismissed — update learning. */
    suspend fun recordDismissal(patternId: Long) {
        learningEngine.recordDismissal(patternId)
        refreshContext()
    }

    /** Called after a proactive event was accepted as useful — update learning. */
    suspend fun recordAccept(patternId: Long) {
        learningEngine.recordAccept(patternId)
        refreshContext()
    }

    suspend fun resetLearnedRoutines() {
        learningEngine.resetAll()
        currentContext = AmbientContext.EMPTY
    }

    /** Diagnostics: latest ambient events (newest first). */
    fun recentEvents(n: Int = 20): List<AmbientEvent> = eventStore.recent(n)

    /** Diagnostics: all learned patterns. */
    suspend fun allPatterns(): List<RoutinePattern> = learningEngine.allPatterns()

    // ── Private ───────────────────────────────────────────────────────────────

    private fun updateContextFromEvent(event: com.jarvis.assistant.core.events.Event) {
        val nowMs = System.currentTimeMillis()
        val ctx = currentContext

        when (event.kind) {
            EventKind.BLUETOOTH_DEVICE_CONNECTED -> {
                val deviceClass = event.payload["device_class"] ?: ""
                val name = event.payload["device_name"] ?: ""
                val isCar = deviceClass.contains("HANDSFREE", ignoreCase = true) ||
                    deviceClass.contains("AUDIO", ignoreCase = true) ||
                    name.contains("car", ignoreCase = true)
                if (isCar) {
                    currentContext = ctx.copy(carBtConnectedMs = nowMs)
                    Log.d(TAG, "[AMBIENT_CTX] car BT connected")
                }
            }
            EventKind.BLUETOOTH_DEVICE_DISCONNECTED -> {
                val deviceClass = event.payload["device_class"] ?: ""
                if (deviceClass.contains("HANDSFREE", ignoreCase = true)) {
                    currentContext = ctx.copy(carBtConnectedMs = null)
                }
            }
            EventKind.PLACE_ARRIVED -> {
                val placeKind = event.payload["place_kind"]
                val bucket = when (placeKind) {
                    "HOME"  -> AmbientLocationBucket.HOME
                    "WORK"  -> AmbientLocationBucket.WORK
                    else    -> AmbientLocationBucket.UNKNOWN
                }
                currentContext = ctx.copy(locationBucket = bucket)
                Log.d(TAG, "[AMBIENT_CTX] location → $bucket")
            }
            EventKind.PLACE_LEFT -> {
                if (event.payload["place_kind"] == "HOME") {
                    currentContext = ctx.copy(locationBucket = AmbientLocationBucket.TRANSIT)
                }
            }
            EventKind.FOREGROUND_APP_CHANGED -> {
                val pkg = event.payload["package_name"] ?: return
                val recent = (ctx.recentAppOpens + RecentAppOpen(pkg, nowMs))
                    .filter { nowMs - it.openedAtMs < ETSY_OPEN_WINDOW_MS * 3 }
                    .takeLast(20)
                currentContext = ctx.copy(recentAppOpens = recent)
            }
            EventKind.SMART_HOME_STATE -> {
                val state = event.payload["state"] ?: return
                val friendlyName = event.payload["friendly_name"]
                    ?: event.payload["entity_id"] ?: return
                val isRunning = state == "on" || state == "running" || state == "printing"
                val isAway = currentContext.locationBucket != AmbientLocationBucket.HOME
                if (isAway && isRunning) {
                    val list = (ctx.haDevicesRunningAway + friendlyName).distinct()
                    currentContext = ctx.copy(haDevicesRunningAway = list)
                } else if (!isRunning) {
                    val list = ctx.haDevicesRunningAway.filter { it != friendlyName }
                    currentContext = ctx.copy(haDevicesRunningAway = list)
                }
            }
            else -> {}
        }

        // Expire stale car BT connection
        val updatedCtx = currentContext
        if (updatedCtx.carBtConnectedMs != null &&
            nowMs - updatedCtx.carBtConnectedMs > CAR_BT_WINDOW_MS) {
            currentContext = updatedCtx.copy(carBtConnectedMs = null)
        }
    }

    private suspend fun refreshContext() {
        if (!settingsProvider().enabled) return
        val patterns = learningEngine.confidentPatterns()

        // Derive leave-lead minutes from LEFT_HOME pattern vs calendar event patterns
        val leaveLead = patterns
            .firstOrNull { it.triggerType == AmbientEventType.LEFT_HOME }
            ?.let { /* how early before events does this pattern fire — simplified */ 15 }

        currentContext = currentContext.copy(
            activeRoutinePatterns      = patterns,
            learnedLeaveLeadMinutes    = leaveLead,
        )
    }
}
