package com.jarvis.assistant.knowledge

import android.util.Log
import com.jarvis.assistant.knowledge.db.entity.WikiPage

/**
 * Resolves entity names to existing WikiPages or creates new stubs.
 * Deduplication is based on normalised title (lowercase trim).
 */
class EntityResolver(private val repo: KnowledgeRepository) {

    companion object {
        private const val TAG = "EntityResolver"
    }

    /**
     * Return the existing WikiPage for [name], or create a new stub if none exists.
     * Type mismatch on existing page is tolerated — same entity, don't duplicate.
     */
    suspend fun resolveOrCreate(name: String, pageType: String): WikiPage {
        val normalized = normalize(name)
        repo.pages.getByNormalizedTitle(normalized)?.let { return it }

        val now  = System.currentTimeMillis()
        val stub = WikiPage(
            pageType        = pageType,
            title           = name.trim(),
            titleNormalized = normalized,
            summary         = "",
            updatedAt       = now,
            confidence      = 0.5f,
            status          = WikiPage.ACTIVE,
            sourceCount     = 0
        )
        val id = repo.pages.insert(stub)
        Log.d(TAG, "Created new page id=$id title=$name type=$pageType")
        return stub.copy(id = id)
    }

    /** FTS-style search — delegates to WikiPageDao.search. */
    suspend fun findSimilar(query: String): List<WikiPage> =
        repo.pages.search(query)

    private fun normalize(name: String): String = name.lowercase().trim()
}
