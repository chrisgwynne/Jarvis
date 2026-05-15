package com.jarvis.assistant.ambient

import android.util.Log
import com.jarvis.assistant.ambient.db.AmbientEventDao
import com.jarvis.assistant.ambient.db.AmbientEventEntity
import com.jarvis.assistant.core.events.EventBus
import com.jarvis.assistant.core.events.EventKind
import com.jarvis.assistant.location.LocationTransition
import com.jarvis.assistant.location.PlaceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * AmbientEventStore — collects raw ambient signals from [EventBus] and
 * keeps them in an in-memory ring buffer (50 events) plus a Room backing
 * store for history and routine learning.
 *
 * Call [attach] once to start subscribing; [detach] to stop.
 */
class AmbientEventStore(
    private val dao: AmbientEventDao,
    private val scope: CoroutineScope,
    private val settingsProvider: () -> AmbientSettings = { AmbientSettings() },
) {
    private val ring = CopyOnWriteArrayList<AmbientEvent>()

    companion object {
        private const val TAG = "AmbientEventStore"
        private const val RING_CAP = 50
        private val TRACKED_APPS = setOf(
            "com.etsy.android",
            "com.shopify.mobile",
            "com.slack",
            "com.microsoft.teams",
        )
        private const val RETENTION_DAYS = 30L
    }

    fun attach() {
        scope.launch(Dispatchers.IO) {
            pruneOld()
            EventBus.events.collect { event ->
                if (!settingsProvider().enabled) return@collect
                val ambient = translate(event) ?: return@collect
                record(ambient)
            }
        }
    }

    fun detach() { /* cancellation is handled by the parent scope */ }

    /** Most recent [n] events from the ring buffer (newest first). */
    fun recent(n: Int = RING_CAP): List<AmbientEvent> =
        ring.reversed().take(n)

    /** Recent events of a specific type from the ring buffer. */
    fun recentOfType(type: AmbientEventType, n: Int = 10): List<AmbientEvent> =
        ring.reversed().filter { it.type == type }.take(n)

    /** Persist an externally-created event (e.g. from Todoist / location engine). */
    fun record(event: AmbientEvent) {
        addToRing(event)
        scope.launch(Dispatchers.IO) {
            try {
                dao.insert(AmbientEventEntity.from(event))
            } catch (e: Exception) {
                Log.w(TAG, "DB write failed for $event", e)
            }
        }
        Log.d(TAG, "[AMBIENT_EVENT] type=${event.type} src=${event.source} loc=${event.locationBucket}")
    }

    /** Load recent events from DB for pattern analysis (suspending). */
    suspend fun loadSince(fromMs: Long): List<AmbientEvent> =
        dao.getSince(fromMs).map { it.toDomain() }

    /** Load count of a specific type since a time (suspending). */
    suspend fun countSince(type: AmbientEventType, fromMs: Long): Int =
        dao.countByTypeSince(type.name, fromMs)

    private fun addToRing(event: AmbientEvent) {
        ring.add(event)
        while (ring.size > RING_CAP) ring.removeAt(0)
    }

    private fun translate(event: com.jarvis.assistant.core.events.Event): AmbientEvent? {
        val now = event.timestamp
        return when (event.kind) {
            EventKind.FOREGROUND_APP_CHANGED -> {
                val pkg = event.payload["package_name"] ?: return null
                if (pkg !in TRACKED_APPS) return null
                AmbientEvent(
                    type = AmbientEventType.APP_OPENED,
                    timestampMs = now,
                    source = "event_bus",
                    metadata = mapOf("package" to pkg),
                    appPackage = pkg,
                )
            }
            EventKind.BLUETOOTH_DEVICE_CONNECTED -> {
                val name = event.payload["device_name"] ?: ""
                val deviceClass = event.payload["device_class"] ?: ""
                val isCarProfile = deviceClass.contains("HANDSFREE", ignoreCase = true) ||
                    deviceClass.contains("AUDIO", ignoreCase = true) ||
                    name.contains("car", ignoreCase = true) ||
                    name.contains("auto", ignoreCase = true) ||
                    name.contains("sync", ignoreCase = true)
                if (!isCarProfile) return null
                AmbientEvent(
                    type = AmbientEventType.CONNECTED_CAR_BLUETOOTH,
                    timestampMs = now,
                    source = "event_bus",
                    metadata = mapOf("device_name" to name),
                )
            }
            EventKind.BLUETOOTH_DEVICE_DISCONNECTED -> {
                val deviceClass = event.payload["device_class"] ?: ""
                if (!deviceClass.contains("HANDSFREE", ignoreCase = true)) return null
                AmbientEvent(
                    type = AmbientEventType.DISCONNECTED_CAR_BLUETOOTH,
                    timestampMs = now,
                    source = "event_bus",
                )
            }
            EventKind.PLACE_ARRIVED, EventKind.GEOFENCE_ENTERED -> {
                val placeKind = event.payload["place_kind"]
                val type = when (placeKind) {
                    "HOME"  -> AmbientEventType.ARRIVED_HOME
                    "WORK"  -> AmbientEventType.ARRIVED_WORK
                    "KNOWN" -> AmbientEventType.ARRIVED_KNOWN_PLACE
                    else    -> null
                } ?: return null
                AmbientEvent(
                    type = type,
                    timestampMs = now,
                    source = "event_bus",
                    locationBucket = when (placeKind) {
                        "HOME" -> AmbientLocationBucket.HOME
                        "WORK" -> AmbientLocationBucket.WORK
                        else   -> AmbientLocationBucket.UNKNOWN
                    },
                )
            }
            EventKind.PLACE_LEFT, EventKind.GEOFENCE_EXITED -> {
                val placeKind = event.payload["place_kind"]
                if (placeKind != "HOME") return null
                AmbientEvent(
                    type = AmbientEventType.LEFT_HOME,
                    timestampMs = now,
                    source = "event_bus",
                    locationBucket = AmbientLocationBucket.HOME,
                )
            }
            EventKind.SMART_HOME_STATE -> {
                val entityId = event.payload["entity_id"] ?: return null
                val state = event.payload["state"] ?: return null
                val friendlyName = event.payload["friendly_name"] ?: entityId
                if (!isPrinterOrWorkshop(entityId, friendlyName)) return null
                if (state != "on" && state != "running" && state != "printing") return null
                AmbientEvent(
                    type = AmbientEventType.HA_DEVICE_RUNNING_AWAY,
                    timestampMs = now,
                    source = "ha",
                    metadata = mapOf("entity_id" to entityId, "friendly_name" to friendlyName),
                )
            }
            EventKind.POWER_CONNECTED -> AmbientEvent(
                type = AmbientEventType.PHONE_CHARGING,
                timestampMs = now,
                source = "event_bus",
            )
            EventKind.POWER_DISCONNECTED -> AmbientEvent(
                type = AmbientEventType.PHONE_UNPLUGGED,
                timestampMs = now,
                source = "event_bus",
            )
            else -> null
        }
    }

    private fun isPrinterOrWorkshop(entityId: String, friendlyName: String): Boolean {
        val lower = (entityId + " " + friendlyName).lowercase()
        return PRINTER_HINTS.any { lower.contains(it) }
    }

    private val PRINTER_HINTS = setOf(
        "printer", "workshop", "3d_print", "3dprint", "laser", "cnc", "lathe", "saw"
    )

    private suspend fun pruneOld() {
        val cutoff = System.currentTimeMillis() - (RETENTION_DAYS * 24 * 60 * 60 * 1000L)
        dao.deleteOlderThan(cutoff)
    }
}
