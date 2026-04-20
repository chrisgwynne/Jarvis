package com.jarvis.assistant.core.telemetry

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * DecisionTraceStore — fire-and-forget persistence of every policy decision
 * cycle. Replaces the RAM-only [com.jarvis.assistant.proactive.ProactiveMetrics]
 * for post-hoc inspection.
 *
 * Writes on a background scope so the decision path never blocks on DB I/O.
 * Failures are logged and swallowed — telemetry must never break the agent.
 */
class DecisionTraceStore(
    private val dao: DecisionTraceDao,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val retainMs: Long = 7L * 24 * 60 * 60 * 1000,
) {
    fun record(
        tickId: String = UUID.randomUUID().toString(),
        outcome: String,
        dispatchedDedupeKey: String? = null,
        snapshotJson: String = "",
        candidatesJson: String = "",
        gatesJson: String = "",
    ) {
        val entity = DecisionTraceEntity(
            createdAtMs = System.currentTimeMillis(),
            tickId = tickId,
            outcome = outcome,
            dispatchedDedupeKey = dispatchedDedupeKey,
            snapshotJson = snapshotJson,
            candidatesJson = candidatesJson,
            gatesJson = gatesJson,
        )
        scope.launch {
            try {
                dao.insert(entity)
            } catch (e: Exception) {
                Log.w(TAG, "insert failed: ${e.message}")
            }
        }
    }

    suspend fun latest(limit: Int = 50): List<DecisionTraceEntity> = dao.latest(limit)

    fun prune() {
        scope.launch {
            try {
                val cutoff = System.currentTimeMillis() - retainMs
                val deleted = dao.deleteOlderThan(cutoff)
                if (deleted > 0) Log.d(TAG, "pruned $deleted traces older than ${retainMs / 3600_000}h")
            } catch (e: Exception) {
                Log.w(TAG, "prune failed: ${e.message}")
            }
        }
    }

    companion object { private const val TAG = "DecisionTraceStore" }
}
