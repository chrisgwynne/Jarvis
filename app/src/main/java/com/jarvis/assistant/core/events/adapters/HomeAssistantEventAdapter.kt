package com.jarvis.assistant.core.events.adapters

import android.util.Log
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.core.events.EventAdapter
import com.jarvis.assistant.core.events.EventKind
import com.jarvis.assistant.core.events.EventPublisher
import com.jarvis.assistant.tools.smart.HomeAssistantClient
import com.jarvis.assistant.tools.smart.HomeAssistantWebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * HomeAssistantEventAdapter — real-time smart-home state events via HA
 * WebSocket, falling back to REST polling if the websocket fails to
 * authenticate or disconnects repeatedly.
 *
 * The websocket path reports every `state_changed` event the moment HA
 * emits it, so motion sensors, locks, and covers flow into the trigger
 * framework with zero poll latency. The fallback path keeps the engine
 * alive on broken connections or HA installs without websocket access.
 *
 * [clientProvider] supplies the REST client (used for fallback polling)
 * and [wsClientProvider] supplies the WebSocket client. Both can return
 * null when credentials aren't configured; the adapter no-ops cleanly.
 */
class HomeAssistantEventAdapter(
    private val clientProvider: () -> HomeAssistantClient?,
    private val wsClientProvider: () -> HomeAssistantWebSocketClient? = { null },
    private val pollIntervalMs: Long = 30_000L,
    private val trackedDomains: Set<String> = setOf(
        "binary_sensor", "lock", "cover", "alarm_control_panel", "light", "switch",
    ),
) : EventAdapter {

    override val name: String = "HomeAssistantEventAdapter"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wsJob: Job? = null
    private var pollJob: Job? = null
    private var publisher: EventPublisher? = null
    private val lastState = mutableMapOf<String, String>()
    @Volatile private var wsFailureCount: Int = 0
    @Volatile private var wsActive: Boolean = false
    @Volatile private var wsClient: HomeAssistantWebSocketClient? = null

    override fun attach(publisher: EventPublisher) {
        if (wsJob != null || pollJob != null) return
        this.publisher = publisher
        wsJob = scope.launch { runWebSocketLoop(publisher) }
        pollJob = scope.launch { runPollLoop(publisher) }
    }

    override fun detach() {
        wsJob?.cancel(); wsJob = null
        pollJob?.cancel(); pollJob = null
        wsClient?.close(); wsClient = null
        wsActive = false
        publisher = null
        lastState.clear()
    }

    private suspend fun runWebSocketLoop(publisher: EventPublisher) {
        while (scope.isActive) {
            val client = wsClientProvider()
            if (client == null) {
                delay(pollIntervalMs)
                continue
            }
            wsClient = client
            try {
                client.connect()
                wsActive = true
                client.stateChanges.collect { change ->
                    if (change.domain !in trackedDomains) return@collect
                    val prev = lastState[change.entityId]
                    lastState[change.entityId] = change.newState
                    if (change.newState == prev) return@collect
                    publisher.publish(
                        Event.of(
                            kind = EventKind.SMART_HOME_STATE,
                            source = "HomeAssistantEventAdapter",
                            payload = buildMap {
                                put("entity_id", change.entityId)
                                put("domain", change.domain)
                                put("state", change.newState)
                                if (prev != null) put("previous_state", prev)
                                else if (change.previousState != null) put("previous_state", change.previousState)
                                change.friendlyName?.let { put("friendly_name", it) }
                            },
                            sensitivity = Event.Sensitivity.PERSONAL,
                            dedupeKey = "ha_${change.entityId}_${change.newState}",
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "ws loop failure: ${e.message}")
            } finally {
                wsActive = false
                client.close()
                wsClient = null
            }
            wsFailureCount += 1
            val backoff = (pollIntervalMs * wsFailureCount.coerceAtMost(4)).coerceAtLeast(5_000L)
            delay(backoff)
        }
    }

    private suspend fun runPollLoop(publisher: EventPublisher) {
        while (scope.isActive) {
            if (!wsActive) {
                try {
                    pollOnce(publisher)
                } catch (e: Exception) {
                    Log.w(TAG, "poll failed: ${e.message}")
                }
            }
            delay(pollIntervalMs)
        }
    }

    private suspend fun pollOnce(publisher: EventPublisher) {
        val client = clientProvider() ?: return
        val entities = client.fetchStatesFresh()
        if (entities.isEmpty()) return
        val firstRun = lastState.isEmpty()
        for (e in entities) {
            if (e.domain !in trackedDomains) continue
            val prev = lastState[e.entityId]
            lastState[e.entityId] = e.state
            if (firstRun) continue
            if (prev != null && prev != e.state) {
                publisher.publish(
                    Event.of(
                        kind = EventKind.SMART_HOME_STATE,
                        source = "HomeAssistantEventAdapter",
                        payload = mapOf(
                            "entity_id" to e.entityId,
                            "domain" to e.domain,
                            "state" to e.state,
                            "previous_state" to prev,
                            "friendly_name" to e.friendlyName,
                        ),
                        sensitivity = Event.Sensitivity.PERSONAL,
                        dedupeKey = "ha_${e.entityId}_${e.state}",
                    )
                )
            }
        }
    }

    companion object { private const val TAG = "HomeAssistantEventAdapter" }
}
