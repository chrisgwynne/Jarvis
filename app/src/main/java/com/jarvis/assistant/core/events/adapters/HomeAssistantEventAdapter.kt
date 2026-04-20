package com.jarvis.assistant.core.events.adapters

import android.util.Log
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.core.events.EventAdapter
import com.jarvis.assistant.core.events.EventKind
import com.jarvis.assistant.core.events.EventPublisher
import com.jarvis.assistant.tools.smart.HomeAssistantClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * HomeAssistantEventAdapter — polls [HomeAssistantClient.fetchStatesFresh]
 * on a configurable interval and emits [EventKind.SMART_HOME_STATE] events
 * when any entity's state changes.
 *
 * A polling adapter is a stopgap; a websocket subscription against the
 * HA WebSocket API would be real-time and lower-overhead. Implementing
 * that requires OkHttp WebSocket + auth handshake plumbing that's outside
 * this sprint. For now, 30s polling proves the inbound channel and
 * unlocks motion/door/window triggers.
 *
 * Supplies [clientProvider] lazily so the adapter can be constructed
 * even when HA credentials aren't configured yet — it simply skips
 * polling until a client is available.
 */
class HomeAssistantEventAdapter(
    private val clientProvider: () -> HomeAssistantClient?,
    private val pollIntervalMs: Long = 30_000L,
    /** Entity domains worth emitting. Filters out noisy `sensor.*` churn. */
    private val trackedDomains: Set<String> = setOf(
        "binary_sensor", "lock", "cover", "alarm_control_panel", "light", "switch",
    ),
) : EventAdapter {

    override val name: String = "HomeAssistantEventAdapter"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var publisher: EventPublisher? = null
    private val lastState = mutableMapOf<String, String>()

    override fun attach(publisher: EventPublisher) {
        if (job != null) return
        this.publisher = publisher
        job = scope.launch {
            while (isActive) {
                try {
                    pollOnce(publisher)
                } catch (e: Exception) {
                    Log.w(TAG, "poll failed: ${e.message}")
                }
                delay(pollIntervalMs)
            }
        }
    }

    override fun detach() {
        job?.cancel()
        job = null
        publisher = null
        lastState.clear()
    }

    private suspend fun pollOnce(publisher: EventPublisher) {
        val client = clientProvider() ?: return
        val entities = client.fetchStatesFresh()
        if (entities.isEmpty()) return

        var firstRun = lastState.isEmpty()
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
