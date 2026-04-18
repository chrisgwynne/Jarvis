package com.jarvis.assistant.knowledge

import android.util.Log
import com.jarvis.assistant.knowledge.db.entity.WikiPage

/**
 * KnowledgeGraphTraversal — follows PageLink edges from seed pages to collect
 * a richer context neighbourhood for a query.
 *
 * Algorithm: breadth-first from each seed page, up to [maxHops] hops.
 * Both forward (fromPageId) and backward (toPageId) links are followed so the
 * graph is traversed as undirected.  Visited page IDs prevent cycles.
 *
 * Use this when a query deserves deeper context beyond the direct title match —
 * e.g. asking about "Sarah's work" can surface "Sarah" → (mentions) → "Acme Corp"
 * → (child_of) → "Acme Products" to build a richer answer.
 */
class KnowledgeGraphTraversal(private val repo: KnowledgeRepository) {

    companion object {
        private const val TAG           = "KnowledgeGraphTraversal"
        private const val DEFAULT_HOPS  = 2
        private const val MAX_PAGES     = 8
        private const val MAX_FACTS     = 4
        private const val MAX_CHARS     = 900
    }

    /**
     * Traverse the knowledge graph starting from [seedPageIds], following links
     * up to [maxHops] hops, and return a compact context block.
     *
     * @param seedPageIds  IDs of the seed pages found by a direct title search.
     * @param maxHops      Maximum number of link-hops to follow (default 2).
     * @return A "[Knowledge]\n…" context block, or "" if nothing found.
     */
    suspend fun traverse(seedPageIds: List<Long>, maxHops: Int = DEFAULT_HOPS): String {
        if (seedPageIds.isEmpty()) return ""

        val visited    = mutableSetOf<Long>()
        val queue      = ArrayDeque<Pair<Long, Int>>()   // (pageId, hopsLeft)
        val collected  = mutableListOf<WikiPage>()

        seedPageIds.forEach { queue.add(it to maxHops) }

        while (queue.isNotEmpty() && collected.size < MAX_PAGES) {
            val (pageId, hops) = queue.removeFirst()
            if (pageId in visited) continue
            visited.add(pageId)

            val page = repo.pages.getById(pageId) ?: continue
            collected.add(page)

            if (hops > 0) {
                // Follow forward links
                repo.links.getLinksFrom(pageId).forEach { link ->
                    if (link.toPageId !in visited) queue.add(link.toPageId to hops - 1)
                }
                // Follow backward links (undirected traversal)
                repo.links.getLinksTo(pageId).forEach { link ->
                    if (link.fromPageId !in visited) queue.add(link.fromPageId to hops - 1)
                }
            }
        }

        if (collected.isEmpty()) return ""

        Log.d(TAG, "Graph traversal: ${collected.size} pages from ${seedPageIds.size} seeds (maxHops=$maxHops)")

        return buildString {
            append("[Knowledge — graph context]\n")
            for (page in collected) {
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
        }.take(MAX_CHARS)
    }

    /**
     * Convenience: run a text search for [query], collect seed page IDs, then
     * traverse the graph.  Returns "" if no seed pages match.
     */
    suspend fun retrieveWithTraversal(query: String, maxHops: Int = DEFAULT_HOPS): String {
        val seeds = repo.pages.search(query).map { it.id }
        return traverse(seeds, maxHops)
    }
}
