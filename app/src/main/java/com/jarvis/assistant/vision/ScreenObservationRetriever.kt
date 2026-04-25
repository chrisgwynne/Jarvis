package com.jarvis.assistant.vision

import android.util.Log
import com.jarvis.assistant.memory.db.dao.MemoryDao
import com.jarvis.assistant.memory.db.entity.MemoryEntry
import com.jarvis.assistant.memory.db.entity.MemoryType

/**
 * ScreenObservationRetriever — read-only helpers for past "look at this"
 * observations.  Pure query layer: no writes, no Vision calls.
 *
 * USAGE:
 *   val latest = retriever.latest()
 *   val gmailScreens = retriever.byApp("Gmail")
 *   val yesterday = retriever.inTimeWindow(startOfYesterday, endOfYesterday)
 *   val iphones  = retriever.byKeyword("iphone")
 */
class ScreenObservationRetriever(
    private val dao: MemoryDao,
    /** Shared analyzer instance — only used as a parser for stored JSON. */
    private val analyzer: VisionScreenAnalyzer
) {

    companion object {
        private const val TAG = "ScreenObservationRetriever"
        /** Upper bound on hits we'll ever decode per call. */
        private const val MAX_LIMIT = 50
    }

    /**
     * Most recently captured observation, or null if none.
     */
    suspend fun latest(): ScreenObservation? =
        dao.getByType(MemoryType.SCREEN_OBSERVATION, limit = 1)
            .firstOrNull()
            ?.toObservation()

    /**
     * Up to [limit] most-recent observations (newest first).
     */
    suspend fun recent(limit: Int = 10): List<ScreenObservation> =
        dao.getByType(MemoryType.SCREEN_OBSERVATION, limit.coerceAtMost(MAX_LIMIT))
            .mapNotNull { it.toObservation() }

    /**
     * Observations whose stored appName matches [appName] case-insensitively.
     * The DAO's searchByKeyword hits the keyword column where appName is
     * persisted as the first token — cheap and accurate for the common case.
     */
    suspend fun byApp(appName: String, limit: Int = 10): List<ScreenObservation> {
        val token = appName.trim().lowercase().ifBlank { return emptyList() }
        return dao.searchByKeyword(token, limit.coerceAtMost(MAX_LIMIT))
            .filter { it.type == MemoryType.SCREEN_OBSERVATION }
            .mapNotNull { it.toObservation() }
            .filter { it.analysis.appName.equals(appName, ignoreCase = true) }
    }

    /**
     * Observations whose keyword index contains [keyword].  Useful for
     * "did I see anything about Tesco?" style questions.
     */
    suspend fun byKeyword(keyword: String, limit: Int = 10): List<ScreenObservation> {
        val token = keyword.trim().lowercase().ifBlank { return emptyList() }
        return dao.searchByKeyword(token, limit.coerceAtMost(MAX_LIMIT))
            .filter { it.type == MemoryType.SCREEN_OBSERVATION }
            .mapNotNull { it.toObservation() }
    }

    /**
     * Observations captured within the inclusive [fromMs, toMs] window.
     */
    suspend fun inTimeWindow(fromMs: Long, toMs: Long): List<ScreenObservation> =
        dao.getByTimeRange(fromMs, toMs)
            .filter { it.type == MemoryType.SCREEN_OBSERVATION }
            .mapNotNull { it.toObservation() }

    // ── internal ──────────────────────────────────────────────────────────────

    private fun MemoryEntry.toObservation(): ScreenObservation? {
        val obs = ScreenObservation.fromStoredContent(id, content, analyzer)
        if (obs == null) {
            Log.w(TAG, "Skipping malformed SCREEN_OBSERVATION row id=$id")
        }
        return obs
    }
}
