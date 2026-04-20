package com.jarvis.assistant.core.events

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * RecentEventBuffer — a fixed-size ring of the most recent events flowing
 * on the bus, so triggers can reason about cross-stream history without
 * each keeping its own subscription.
 *
 * [snapshot] returns a filtered view in newest-first order. Composite
 * triggers use this to say "did WIFI_SSID_CHANGED happen in the last 60s?"
 * or "any FOREGROUND_APP_CHANGED since the last DRIVING_MODE_ON?"
 *
 * Bounded in count and age; older entries evict on every write. Safe to
 * read from any thread via the intrinsic lock.
 */
class RecentEventBuffer(
    private val capacity: Int = 200,
    private val maxAgeMs: Long = 10 * 60 * 1000L,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val buffer = ArrayDeque<Event>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun attach(flow: Flow<Event> = EventBus.events) {
        if (job != null) return
        job = scope.launch {
            try {
                flow.collect(::add)
            } catch (e: Exception) {
                Log.w(TAG, "collect failed: ${e.message}")
            }
        }
    }

    fun detach() {
        job?.cancel()
        job = null
        synchronized(lock) { buffer.clear() }
    }

    fun add(event: Event) = synchronized(lock) {
        evictStale(nowMs())
        buffer.addLast(event)
        while (buffer.size > capacity) buffer.removeFirst()
    }

    /** Snapshot newest-first, within [maxAgeMs]. */
    fun snapshot(maxAgeMs: Long = this.maxAgeMs): List<Event> = synchronized(lock) {
        val cutoff = nowMs() - maxAgeMs
        buffer.reversed().filter { it.tsMillis >= cutoff }
    }

    /** Snapshot filtered to the given kinds. */
    fun snapshotOfKind(kind: EventKind, maxAgeMs: Long = this.maxAgeMs): List<Event> =
        snapshot(maxAgeMs).filter { it.kind == kind }

    fun lastOfKind(kind: EventKind): Event? = synchronized(lock) {
        buffer.reversed().firstOrNull { it.kind == kind }
    }

    private fun evictStale(now: Long) {
        val cutoff = now - maxAgeMs
        while (buffer.isNotEmpty() && buffer.peekFirst().tsMillis < cutoff) {
            buffer.removeFirst()
        }
    }

    companion object { private const val TAG = "RecentEventBuffer" }
}
