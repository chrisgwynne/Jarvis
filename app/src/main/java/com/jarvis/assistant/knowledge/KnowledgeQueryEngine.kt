package com.jarvis.assistant.knowledge

import android.util.Log
import com.jarvis.assistant.knowledge.db.entity.FactRecord
import com.jarvis.assistant.knowledge.db.entity.WikiPage

/**
 * KnowledgeQueryEngine — retrieves compiled knowledge for prompt injection.
 *
 * Searches wiki_pages (title + summary LIKE match) and assembles a compact
 * context string. No LLM call here — fast, synchronous-style lookup.
 */
class KnowledgeQueryEngine(private val repo: KnowledgeRepository) {

    companion object {
        private const val TAG        = "KnowledgeQueryEngine"
        private const val MAX_PAGES  = 3
        private const val MAX_FACTS  = 5
        private const val MAX_CHARS  = 700
    }

    /**
     * Search wiki pages and facts for [query].
     * Returns a compact context block for prompt injection, or "" if nothing found.
     */
    suspend fun retrieveContext(query: String): String {
        if (query.isBlank()) return ""

        // Extract the most useful search term (first non-trivial word if query is long)
        val searchTerm = extractSearchTerm(query)
        val pages = repo.pages.search(searchTerm).take(MAX_PAGES)
        if (pages.isEmpty()) return ""

        return buildString {
            append("[Knowledge]\n")
            for (page in pages) {
                append("${page.title} (${page.pageType})")
                if (page.summary.isNotBlank()) append(": ${page.summary}")
                append("\n")

                val facts = repo.facts.getActiveForPage(page.id).take(MAX_FACTS)
                if (facts.isNotEmpty()) {
                    append("  ")
                    append(facts.joinToString(" · ") { "${it.predicate}: ${it.objectValue}" })
                    append("\n")
                }
            }
        }.take(MAX_CHARS).also {
            Log.d(TAG, "Retrieved ${pages.size} pages for query: $searchTerm")
        }
    }

    /**
     * Heuristic: is this query asking about something Jarvis might have compiled knowledge on?
     * Used to decide whether to trigger context retrieval.
     */
    fun isKnowledgeQuery(query: String): Boolean {
        val lower = query.lowercase()
        return KNOWLEDGE_TRIGGERS.any { lower.contains(it) }
    }

    private val KNOWLEDGE_TRIGGERS = listOf(
        "what do you know", "tell me about", "who is", "what is",
        "what have you learned", "what do you remember about",
        "summarise", "summarize", "what happened", "remind me about",
        "what did you learn", "what have i told you", "what's the story"
    )

    /**
     * Extract the most meaningful search term from a longer query.
     * E.g. "what do you know about Sarah?" → "Sarah"
     */
    private fun extractSearchTerm(query: String): String {
        // Try to extract the subject after common question phrasings
        val about = Regex("""(?:about|regarding|on|for)\s+([a-z0-9][\w\s]{1,40})""", RegexOption.IGNORE_CASE)
            .find(query)?.groupValues?.get(1)?.trim()
        if (!about.isNullOrBlank()) return about

        // "who is X" / "what is X"
        val whoWhat = Regex("""^(?:who|what) (?:is|are|was) ([a-z0-9][\w\s]{1,40})""", RegexOption.IGNORE_CASE)
            .find(query)?.groupValues?.get(1)?.trim()
        if (!whoWhat.isNullOrBlank()) return whoWhat

        // Fall back to the full query (LIKE search will handle it)
        return query.take(60)
    }
}
