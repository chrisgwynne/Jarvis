package com.jarvis.assistant.vision

import android.util.Log
import com.jarvis.assistant.memory.db.dao.MemoryDao
import com.jarvis.assistant.memory.db.entity.MemoryEntry
import com.jarvis.assistant.memory.db.entity.MemoryType

/**
 * ScreenObservationRepository — the single write path for screen-observation
 * memories.
 *
 * STORAGE POLICY:
 *   * Never persist a screen whose [VisionScreenAnalyzer.ScreenAnalysis.sensitive]
 *     flag is true.
 *   * Never persist a screen whose confidence is below [MIN_CONFIDENCE].
 *   * Reuse the existing `memory_entries` table; SCREEN_OBSERVATION rows
 *     default to the lowest importance so pruning trims them first.
 *
 * Screenshot files are written by [ScreenshotCaptureService] and live
 * independently in app-private storage; if an observation's row is pruned
 * by [MemoryDao.pruneOlderThan], the associated PNG can be deleted in a
 * future cleanup pass — this repository does not own image lifecycle.
 */
class ScreenObservationRepository(private val dao: MemoryDao) {

    companion object {
        private const val TAG = "ScreenObservationRepo"
        /** Threshold from the product spec — weaker observations are dropped. */
        const val MIN_CONFIDENCE = 0.65

        /** Low importance → pruned before preferences / facts / routines. */
        private const val IMPORTANCE = 0.30f

        /** Keyword cap kept small to stay under SQLite index cost. */
        private const val MAX_KEYWORDS = 12
    }

    sealed class SaveResult {
        data class Saved(val id: Long) : SaveResult()
        object SkippedSensitive : SaveResult()
        data class SkippedLowConfidence(val confidence: Double) : SaveResult()
    }

    /**
     * Persist [analysis] if it passes the sensitivity + confidence gates.
     * Returns the memory row id on success, or a skip reason otherwise.
     */
    suspend fun save(
        analysis: VisionScreenAnalyzer.ScreenAnalysis,
        screenshotPath: String?,
        foregroundPackage: String?,
        capturedAtMs: Long
    ): SaveResult {
        if (analysis.sensitive) {
            Log.i(TAG, "Skipping sensitive screen observation (app=${analysis.appName})")
            return SaveResult.SkippedSensitive
        }
        if (analysis.confidence < MIN_CONFIDENCE) {
            Log.i(TAG, "Skipping low-confidence observation (${analysis.confidence})")
            return SaveResult.SkippedLowConfidence(analysis.confidence)
        }

        val content = ScreenObservation.toStoredContent(
            analysis          = analysis,
            screenshotPath    = screenshotPath,
            foregroundPackage = foregroundPackage,
            capturedAtMs      = capturedAtMs
        )
        val keywords = buildKeywords(analysis, foregroundPackage)

        val id = dao.insert(
            MemoryEntry(
                type             = MemoryType.SCREEN_OBSERVATION,
                content          = content,
                keywords         = keywords,
                createdAt        = capturedAtMs,
                lastAccessedAt   = capturedAtMs,
                importanceScore  = IMPORTANCE
            )
        )
        Log.d(TAG, "Saved screen observation id=$id app=${analysis.appName} " +
                   "type=${analysis.screenType}")
        return SaveResult.Saved(id)
    }

    /**
     * Build a comma-separated keyword blob for [MemoryDao.searchByKeyword].
     * Lower-case, de-duplicated, capped at [MAX_KEYWORDS].
     */
    private fun buildKeywords(
        analysis: VisionScreenAnalyzer.ScreenAnalysis,
        foregroundPackage: String?
    ): String {
        val tokens = LinkedHashSet<String>()
        fun add(s: String?) {
            if (s.isNullOrBlank()) return
            val t = s.lowercase().trim()
            if (t.length in 2..40) tokens += t
        }
        add(analysis.appName)
        add(analysis.screenType)
        foregroundPackage?.substringAfterLast('.')?.let(::add)
        analysis.importantText.forEach(::add)
        analysis.brands.forEach(::add)
        analysis.products.forEach(::add)
        analysis.people.forEach(::add)
        return tokens.take(MAX_KEYWORDS).joinToString(",")
    }
}
