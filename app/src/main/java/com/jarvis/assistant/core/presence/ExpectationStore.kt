package com.jarvis.assistant.core.presence

import android.util.Log
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.core.events.EventBus
import com.jarvis.assistant.core.events.EventKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * ExpectationStore — short-term anticipations the agent is holding, with
 * optional time or event-kind triggers.
 *
 * Two use cases:
 *   1. "I'll be home by 6" → expect(label="user home by 6", triggerAtMs=…,
 *      expiresAtMs=…). Surfaces in the system prompt while pending; ages
 *      out if not fulfilled.
 *   2. "User usually plugs in at 22:00" → expect(label="charger in 30m",
 *      triggerEventKind=POWER_CONNECTED). Consumed by any arriving
 *      POWER_CONNECTED event within the window.
 *
 * API is suspend-based; backing store is Room for durability across
 * process death. [attach] subscribes to the bus so event-kind
 * expectations auto-resolve without manual polling.
 */
class ExpectationStore(
    private val dao: ExpectationDao,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private var job: Job? = null

    suspend fun expect(
        label: String,
        triggerAtMs: Long? = null,
        triggerEventKind: EventKind? = null,
        expiresInMs: Long = DEFAULT_TTL_MS,
        sourceTranscript: String? = null,
    ): ExpectationEntity {
        val now = System.currentTimeMillis()
        val entity = ExpectationEntity(
            label = label,
            triggerAtMs = triggerAtMs,
            triggerEventKind = triggerEventKind?.name,
            createdAtMs = now,
            expiresAtMs = now + expiresInMs,
            sourceTranscript = sourceTranscript,
        )
        val id = dao.insert(entity)
        return entity.copy(id = id)
    }

    suspend fun resolve(id: Long) = dao.setStatus(id, ExpectationEntity.STATUS_FULFILLED)

    suspend fun pending(): List<ExpectationEntity> {
        val now = System.currentTimeMillis()
        dao.expireOverdue(now)
        return dao.pending(now)
    }

    suspend fun dueByTime(): List<ExpectationEntity> =
        dao.dueByTime(System.currentTimeMillis())

    suspend fun prune(retentionMs: Long = 7 * 24 * 60 * 60 * 1000L) {
        val cutoff = System.currentTimeMillis() - retentionMs
        dao.pruneOlderThan(cutoff)
    }

    /**
     * Build a short prompt fragment from currently-pending expectations.
     * Returns "" when empty so callers can append unconditionally.
     */
    suspend fun toPromptFragment(): String {
        val p = pending()
        if (p.isEmpty()) return ""
        val top = p.take(4).joinToString("; ") { it.label }
        return "[Short-term expectations — shape tone, don't cite: $top.]"
    }

    /**
     * Subscribe to [EventBus] and auto-fulfil any pending expectation
     * whose [triggerEventKind] matches an incoming event.
     */
    fun attach(flow: Flow<Event> = EventBus.events) {
        if (job != null) return
        job = scope.launch {
            try {
                flow.collect { e -> maybeResolve(e) }
            } catch (e: Exception) {
                Log.w(TAG, "subscription failed: ${e.message}")
            }
        }
    }

    fun detach() {
        job?.cancel()
        job = null
    }

    private suspend fun maybeResolve(event: Event) {
        val matches = dao.pendingForEvent(event.kind.name, System.currentTimeMillis())
        for (match in matches) {
            dao.setStatus(match.id, ExpectationEntity.STATUS_FULFILLED)
        }
    }

    companion object {
        private const val TAG = "ExpectationStore"
        const val DEFAULT_TTL_MS = 6L * 60 * 60 * 1000
    }
}
