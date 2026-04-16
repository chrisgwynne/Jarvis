package com.jarvis.assistant.proactive

import java.util.concurrent.ConcurrentHashMap

/**
 * CooldownStore — thread-safe in-memory record of when each dedupeKey was
 * last surfaced and when any action was last surfaced globally.
 *
 * Designed for high-frequency reads (every polling tick) with infrequent
 * writes (only when an action is actually dispatched).  All operations are
 * O(1) and lock-free for reads thanks to [ConcurrentHashMap] and [@Volatile].
 *
 * The store is intentionally ephemeral — it resets when the process is
 * killed.  Cooldowns are a UX anti-annoyance mechanism; missing one
 * surfacing per app restart is an acceptable trade-off versus the complexity
 * of persisting to disk.
 */
class CooldownStore {

    private val lastSurfacedMs = ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastGlobalSurfaceMs: Long = Long.MIN_VALUE

    // ── Query ─────────────────────────────────────────────────────────────────

    /**
     * Returns true if [dedupeKey] was surfaced within [cooldownMs] milliseconds
     * of [System.currentTimeMillis].
     *
     * A key that has never been surfaced is never on cooldown.
     */
    fun isOnCooldown(dedupeKey: String, cooldownMs: Long): Boolean {
        val last = lastSurfacedMs[dedupeKey] ?: return false
        return (System.currentTimeMillis() - last) < cooldownMs
    }

    /**
     * Milliseconds since [dedupeKey] was last surfaced.
     *
     * Returns [Long.MAX_VALUE] if the key has never been surfaced, which makes
     * it trivially larger than any cooldown window.
     */
    fun msSinceSurfaced(dedupeKey: String): Long {
        val last = lastSurfacedMs[dedupeKey] ?: return Long.MAX_VALUE
        return System.currentTimeMillis() - last
    }

    /**
     * Milliseconds since any action was last surfaced globally.
     *
     * Returns [Long.MAX_VALUE] if nothing has ever been surfaced, which means
     * the global gap check is always satisfied on first run.
     */
    fun msSinceLastGlobalSurface(): Long {
        if (lastGlobalSurfaceMs == Long.MIN_VALUE) return Long.MAX_VALUE
        return System.currentTimeMillis() - lastGlobalSurfaceMs
    }

    // ── Mutation ──────────────────────────────────────────────────────────────

    /**
     * Record that [dedupeKey] was surfaced right now.
     *
     * Updates both the per-key timestamp and the global last-surface timestamp
     * so that [isOnCooldown] and [msSinceLastGlobalSurface] reflect the event.
     */
    fun markSurfaced(dedupeKey: String) {
        val now = System.currentTimeMillis()
        lastSurfacedMs[dedupeKey] = now
        lastGlobalSurfaceMs = now
    }

    /**
     * Clear all recorded timestamps.
     *
     * Intended for use in unit tests so each test case starts with a clean slate
     * without needing to instantiate a new [CooldownStore].
     */
    fun reset() {
        lastSurfacedMs.clear()
        lastGlobalSurfaceMs = Long.MIN_VALUE
    }
}
