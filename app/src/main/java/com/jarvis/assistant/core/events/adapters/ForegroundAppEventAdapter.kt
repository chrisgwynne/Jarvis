package com.jarvis.assistant.core.events.adapters

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.util.Log
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.core.events.EventAdapter
import com.jarvis.assistant.core.events.EventKind
import com.jarvis.assistant.core.events.EventPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ForegroundAppEventAdapter — polls [UsageStatsManager] for the current
 * foreground app and emits FOREGROUND_APP_CHANGED events on transitions.
 *
 * Guarded by the PACKAGE_USAGE_STATS app-op — if the user has not granted
 * "Usage access" via Settings, [attach] becomes a no-op and no events
 * are published. The adapter still safely attaches so it can start
 * working the moment permission is granted without a service restart.
 */
class ForegroundAppEventAdapter(
    private val context: Context,
    private val pollIntervalMs: Long = 5_000L,
) : EventAdapter {

    override val name: String = "ForegroundAppEventAdapter"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var publisher: EventPublisher? = null
    @Volatile private var lastPackage: String? = null

    override fun attach(publisher: EventPublisher) {
        if (job != null) return
        this.publisher = publisher
        job = scope.launch {
            while (isActive) {
                try {
                    if (hasUsageAccess()) pollOnce(publisher)
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
        lastPackage = null
    }

    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = try {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } catch (_: Throwable) {
            return false
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun pollOnce(publisher: EventPublisher) {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - pollIntervalMs * 2, now)
        val e = UsageEvents.Event()
        var latest: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) latest = e.packageName
        }
        if (latest != null && latest != lastPackage) {
            val previous = lastPackage
            lastPackage = latest
            publisher.publish(
                Event.of(
                    kind = EventKind.FOREGROUND_APP_CHANGED,
                    source = "ForegroundAppEventAdapter",
                    payload = buildMap {
                        put("app_package", latest)
                        if (previous != null) put("previous_app_package", previous)
                    },
                    sensitivity = Event.Sensitivity.PERSONAL,
                    dedupeKey = "fg_$latest",
                )
            )
        }
    }

    companion object { private const val TAG = "ForegroundAppEventAdapter" }
}
