package com.jarvis.assistant.proactive

import android.util.Log
import com.jarvis.assistant.proactive.db.ProactiveCooldownDao
import com.jarvis.assistant.proactive.db.ProactiveCooldownEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * CooldownStore — thread-safe record of when each dedupeKey was last
 * surfaced and when any action was last surfaced globally.
 *
 * Reads are O(1) and lock-free (backing [ConcurrentHashMap] + `@Volatile`) so
 * the polling loop stays fast.  Writes fan out: the in-memory map is updated
 * synchronously, and if a [ProactiveCooldownDao] is supplied the change is
 * also queued for persistent storage on an IO coroutine.
 *
 * Process-death behaviour: when constructed with a DAO the store rehydrates
 * from disk on init.  The initial load is a single short suspend call done in
 * the constructor via [runBlocking] on IO — this runs exactly once per
 * process and happens before the proactive loop makes its first tick, so
 * there is no race between rehydration and the first read.
 *
 * The DAO is optional so unit tests (and [ProactiveSimulator]) can construct
 * a pure in-memory instance with no Room dependency.
 */
class CooldownStore(
    /** Soft cap on the per-key maps so dynamic dedupe keys don't accumulate forever. */
    private val maxKeys: Int = 512,
    /** Entries untouched for this long are eligible for eviction. */
    private val maxAgeMs: Long = 24 * 60 * 60 * 1000L,
    /** Optional persistent backing store — null = pure in-memory (legacy / tests). */
    private val dao: ProactiveCooldownDao? = null,
    /** Scope used for background writes when [dao] is non-null. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    companion object { private const val TAG = "CooldownStore" }

    private val lastSurfacedMs = ConcurrentHashMap<String, Long>()
    private val ignoreCountByKey = ConcurrentHashMap<String, Int>()

    @Volatile
    private var lastGlobalSurfaceMs: Long = Long.MIN_VALUE

    init {
        if (dao != null) hydrateFromDao()
    }

    /**
     * Pulls every row out of the DAO into the in-memory maps.  Blocks the
     * constructor briefly so the first [isOnCooldown] read after `new` sees
     * the persisted state.  Measured hydration is a single short query — a
     * handful of rows in practice — so blocking is acceptable and avoids a
     * cold-start race window.
     */
    private fun hydrateFromDao() {
        try {
            runBlocking {
                dao!!.getAll().forEach { row ->
                    if (row.dedupeKey == ProactiveCooldownEntity.GLOBAL_KEY) {
                        lastGlobalSurfaceMs = row.lastSurfacedMs
                    } else {
                        lastSurfacedMs[row.dedupeKey] = row.lastSurfacedMs
                        if (row.ignoreCount > 0) ignoreCountByKey[row.dedupeKey] = row.ignoreCount
                    }
                }
            }
            Log.d(TAG, "Hydrated ${lastSurfacedMs.size} cooldown keys from disk")
        } catch (e: Exception) {
            // A corrupt / unreadable persistent store must NEVER break the
            // proactive loop — fall back to empty in-memory state and log.
            Log.w(TAG, "Cooldown hydration failed; starting fresh: ${e.message}")
        }
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    fun isOnCooldown(dedupeKey: String, cooldownMs: Long): Boolean {
        val last = lastSurfacedMs[dedupeKey] ?: return false
        return (System.currentTimeMillis() - last) < cooldownMs
    }

    fun msSinceSurfaced(dedupeKey: String): Long {
        val last = lastSurfacedMs[dedupeKey] ?: return Long.MAX_VALUE
        return System.currentTimeMillis() - last
    }

    fun msSinceLastGlobalSurface(): Long {
        if (lastGlobalSurfaceMs == Long.MIN_VALUE) return Long.MAX_VALUE
        return System.currentTimeMillis() - lastGlobalSurfaceMs
    }

    fun ignoreCount(dedupeKey: String): Int = ignoreCountByKey[dedupeKey] ?: 0

    // ── Mutation ──────────────────────────────────────────────────────────────

    fun markSurfaced(dedupeKey: String) {
        val now = System.currentTimeMillis()
        lastSurfacedMs[dedupeKey] = now
        lastGlobalSurfaceMs = now
        if (lastSurfacedMs.size > maxKeys) evictStale(now)
        persist(dedupeKey, now, ignoreCountByKey[dedupeKey] ?: 0)
        persist(ProactiveCooldownEntity.GLOBAL_KEY, now, 0)
    }

    fun markIgnored(dedupeKey: String) {
        // Guard: a verdict only makes sense for a dispatch we actually surfaced.
        // Incrementing the counter before the surface call leaves stale state
        // that markSurfaced will later persist with a fresh timestamp — so
        // a future, unrelated surface would inherit someone else's ignore count.
        val last = lastSurfacedMs[dedupeKey] ?: return
        val newCount = ignoreCountByKey.merge(dedupeKey, 1) { old, _ -> old + 1 } ?: 1
        persist(dedupeKey, last, newCount)
    }

    fun markAccepted(dedupeKey: String) {
        val last = lastSurfacedMs[dedupeKey] ?: return
        ignoreCountByKey.remove(dedupeKey)
        persist(dedupeKey, last, 0)
    }

    fun reset() {
        lastSurfacedMs.clear()
        ignoreCountByKey.clear()
        lastGlobalSurfaceMs = Long.MIN_VALUE
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun evictStale(now: Long) {
        val cutoff = now - maxAgeMs
        val removedKeys = lastSurfacedMs.entries
            .filter { it.value < cutoff }
            .map { it.key }
        removedKeys.forEach { key ->
            lastSurfacedMs.remove(key)
            ignoreCountByKey.remove(key)
        }
        if (dao != null && removedKeys.isNotEmpty()) {
            scope.launch {
                try {
                    dao.deleteExpired(cutoff)
                } catch (e: Exception) {
                    Log.w(TAG, "deleteExpired failed: ${e.message}")
                }
            }
        }
    }

    private fun persist(key: String, lastMs: Long, ignoreCount: Int) {
        val d = dao ?: return
        scope.launch {
            try {
                d.upsert(ProactiveCooldownEntity(key, lastMs, ignoreCount))
            } catch (e: Exception) {
                Log.w(TAG, "upsert('$key') failed: ${e.message}")
            }
        }
    }
}
