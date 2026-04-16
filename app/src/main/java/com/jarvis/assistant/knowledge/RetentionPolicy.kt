package com.jarvis.assistant.knowledge

import android.util.Log
import com.jarvis.assistant.knowledge.db.entity.KnowledgeLogEntry
import com.jarvis.assistant.knowledge.db.entity.KnowledgeSource

/**
 * RetentionPolicy — compacts the knowledge base by pruning transient sources
 * and old log entries. Run daily from JarvisRuntime.
 *
 * POLICY
 * ──────
 * TRANSIENT sources  → prune after 7 days  (if already compiled)
 * SHORT_TERM sources → prune after 30 days (if already compiled)
 * LONG_TERM sources  → prune after 365 days
 * Knowledge log      → prune after 30 days
 * Resolved contradictions → prune after 90 days
 */
class RetentionPolicy(private val repo: KnowledgeRepository) {

    companion object {
        private const val TAG = "RetentionPolicy"
    }

    data class RetentionConfig(
        val transientTtlMs:    Long = 7L   * 24 * 3_600_000,
        val shortTermTtlMs:    Long = 30L  * 24 * 3_600_000,
        val longTermTtlMs:     Long = 365L * 24 * 3_600_000,
        val logRetentionMs:    Long = 30L  * 24 * 3_600_000,
        val contradictionTtlMs:Long = 90L  * 24 * 3_600_000
    )

    data class CompactionResult(
        val sourcesDeleted: Int,
        val logEntriesPruned: Int,
        val summary: String
    )

    suspend fun compact(config: RetentionConfig = RetentionConfig()): CompactionResult {
        val now = System.currentTimeMillis()

        val deletedTransient  = repo.sources.pruneOlderThan(now - config.transientTtlMs,  KnowledgeSource.TRANSIENT)
        val deletedShortTerm  = repo.sources.pruneOlderThan(now - config.shortTermTtlMs,  KnowledgeSource.SHORT_TERM)
        val deletedLongTerm   = repo.sources.pruneOlderThan(now - config.longTermTtlMs,   KnowledgeSource.LONG_TERM)
        val prunedLog         = repo.log.pruneOlderThan(now - config.logRetentionMs)
        val prunedContradictions = repo.contradictions.pruneResolved(now - config.contradictionTtlMs)

        val totalSources = deletedTransient + deletedShortTerm + deletedLongTerm
        val summary = "Compact: removed $totalSources sources, $prunedLog log entries, $prunedContradictions resolved contradictions"

        repo.log.insert(KnowledgeLogEntry(
            operationType = KnowledgeLogEntry.COMPACT,
            summary       = summary
        ))
        Log.d(TAG, summary)

        return CompactionResult(totalSources, prunedLog, summary)
    }
}
