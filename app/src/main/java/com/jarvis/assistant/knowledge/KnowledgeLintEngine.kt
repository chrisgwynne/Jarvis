package com.jarvis.assistant.knowledge

import android.util.Log
import com.jarvis.assistant.knowledge.db.entity.KnowledgeLogEntry
import com.jarvis.assistant.knowledge.db.entity.WikiPage

/**
 * KnowledgeLintEngine — lightweight periodic health checks on the knowledge base.
 *
 * Run once a day (triggered by JarvisRuntime on session close or boot).
 * Results are logged; callers can use the LintReport to surface issues.
 */
class KnowledgeLintEngine(private val repo: KnowledgeRepository) {

    companion object {
        private const val TAG         = "KnowledgeLintEngine"
        private const val STALE_MS    = 30L * 24 * 3_600_000  // 30 days
        private const val ORPHAN_CHECK_LIMIT = 50
    }

    data class LintReport(
        val stalePages: List<WikiPage>,
        val orphanPageIds: List<Long>,
        val unresolvedContradictions: Int,
        val uncompiledSourceCount: Int,
        val summary: String
    )

    suspend fun runLint(): LintReport {
        val now      = System.currentTimeMillis()
        val cutoff   = now - STALE_MS

        val stale    = repo.pages.getStale(cutoff)
        val active   = repo.pages.getActive().take(ORPHAN_CHECK_LIMIT)
        val orphans  = active.filter { page ->
            page.sourceCount == 0 && repo.facts.getActiveForPage(page.id).isEmpty()
        }.map { it.id }
        val contradictions = repo.contradictions.getUnresolved().size
        val uncompiled     = repo.sources.countUncompiled()

        val summary = buildString {
            append("Lint: ${stale.size} stale pages")
            if (orphans.isNotEmpty()) append(", ${orphans.size} orphan pages")
            if (contradictions > 0)  append(", $contradictions unresolved contradictions")
            if (uncompiled > 0)      append(", $uncompiled uncompiled sources")
        }

        repo.log.insert(KnowledgeLogEntry(
            operationType = KnowledgeLogEntry.LINT,
            summary       = summary
        ))
        Log.d(TAG, summary)

        return LintReport(stale, orphans, contradictions, uncompiled, summary)
    }

    /** Archive pages identified as orphans during lint. */
    suspend fun archiveOrphans(orphanIds: List<Long>) {
        val now = System.currentTimeMillis()
        for (id in orphanIds) {
            repo.pages.archivePage(id, now)
        }
        if (orphanIds.isNotEmpty()) {
            Log.d(TAG, "Archived ${orphanIds.size} orphan pages")
        }
    }
}
