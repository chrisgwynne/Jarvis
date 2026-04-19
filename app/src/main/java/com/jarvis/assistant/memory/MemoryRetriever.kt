package com.jarvis.assistant.memory

import android.util.Log
import com.jarvis.assistant.memory.db.dao.MemoryDao
import com.jarvis.assistant.memory.db.entity.MemoryEntry
import com.jarvis.assistant.memory.db.entity.MemoryType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * MemoryRetriever — finds relevant memories for a given query.
 *
 * SCORING MODEL:
 *   When [embeddingEngine] is loaded (MiniLM TFLite asset present):
 *     finalScore = semantic×0.50 + recency×0.30 + importance×0.15 + access×0.05
 *   Fallback (no model):
 *     finalScore = keyword×0.45 + recency×0.35 + importance×0.15 + access×0.05
 */
class MemoryRetriever(
    private val dao: MemoryDao,
    private val embeddingEngine: MemoryEmbeddingEngine? = null
) {

    companion object {
        private const val TAG = "MemoryRetriever"
        private const val EMBEDDING_CACHE_SIZE = 8
        private val STOP_WORDS = setOf(
            "the", "a", "an", "is", "was", "did", "what", "how", "when", "where",
            "who", "i", "me", "my", "we", "you", "it", "that", "this", "to", "of",
            "and", "or", "in", "on", "at", "for", "with", "about", "by", "from",
            "do", "does", "can", "could", "would", "should", "have", "had", "been"
        )
    }

    /**
     * Tiny LRU of the last N query → embedding pairs.  Follow-up turns repeat
     * phrasings ("what's next", "and then", "tell me more about that"), so
     * caching the embedding per normalised-query text saves a TFLite forward
     * pass that isn't free on lower-end devices.
     */
    private val embeddingCache = object : LinkedHashMap<String, FloatArray>(
        EMBEDDING_CACHE_SIZE, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: Map.Entry<String, FloatArray>): Boolean =
            size > EMBEDDING_CACHE_SIZE
    }

    private fun cachedEmbed(query: String): FloatArray? {
        val engine = embeddingEngine ?: return null
        val key = query.trim().lowercase()
        synchronized(embeddingCache) { embeddingCache[key]?.let { return it } }
        val fresh = engine.embed(query)
        if (fresh != null) synchronized(embeddingCache) { embeddingCache[key] = fresh }
        return fresh
    }

    /**
     * Retrieve up to [limit] memories most relevant to [query].
     * Side-effect: increments accessCount on returned entries.
     */
    suspend fun retrieveRelevant(query: String, limit: Int = 4): List<MemoryEntry> {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return dao.getRecent(limit)

        // Fetch candidates in parallel: recent entries + keyword-matched entries run concurrently,
        // saving ~60-100 ms compared to the previous serial execution (up to 4 Room queries → 1 batch).
        val candidates = coroutineScope {
            val recentJob = async { dao.getRecent(limit * 5) }
            val keywordJobs = tokens.take(3).map { token ->
                async { dao.searchByKeyword(token, limit * 3) }
            }
            buildSet {
                addAll(recentJob.await())
                keywordJobs.forEach { addAll(it.await()) }
            }
        }.toList()

        // Optionally embed the query for semantic similarity scoring
        val queryEmbedding = cachedEmbed(query)

        val scored = candidates.map { entry ->
            val recency = recencyScore(entry.createdAt)
            val access  = minOf(entry.accessCount / 10f, 1f)
            val total   = if (queryEmbedding != null && entry.embedding != null) {
                val entryEmb = MemoryEmbeddingEngine.fromByteArray(entry.embedding)
                val semantic = MemoryEmbeddingEngine.cosineSimilarity(queryEmbedding, entryEmb)
                semantic * 0.50f + recency * 0.30f + entry.importanceScore * 0.15f + access * 0.05f
            } else {
                val kw = scoreKeywords(entry.keywords.split(","), tokens)
                kw * 0.45f + recency * 0.35f + entry.importanceScore * 0.15f + access * 0.05f
            }
            Pair(entry, total)
        }

        val result = scored.sortedByDescending { it.second }
            .take(limit)
            .map { it.first }

        // Record access for returned memories
        result.forEach { dao.recordAccess(it.id) }

        Log.d(TAG, "Retrieved ${result.size} memories for: \"${query.take(50)}\"")
        return result
    }

    /**
     * Retrieve all memories from a specific time window.
     * Used for queries like "what did I ask yesterday?".
     */
    suspend fun retrieveForTimeRange(fromMs: Long, toMs: Long): List<MemoryEntry> =
        dao.getByTimeRange(fromMs, toMs)

    /**
     * Retrieve memories of a specific type (e.g. PREFERENCE, TASK).
     */
    suspend fun retrieveByType(type: MemoryType, limit: Int = 10): List<MemoryEntry> =
        dao.getByType(type, limit)

    // ── Scoring helpers ───────────────────────────────────────────────────────

    private fun tokenize(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 && it !in STOP_WORDS }
            .toSet()

    private fun scoreKeywords(entryTokens: List<String>, queryTokens: Set<String>): Float {
        if (queryTokens.isEmpty()) return 0f
        val hits = entryTokens.count { it.trim() in queryTokens }
        return hits.toFloat() / queryTokens.size.coerceAtLeast(1).toFloat()
    }

    private fun recencyScore(createdAt: Long): Float {
        val ageH = (System.currentTimeMillis() - createdAt) / 3_600_000f
        return when {
            ageH < 1f     -> 1.0f
            ageH < 24f    -> 0.85f
            ageH < 168f   -> 0.60f   // 1 week
            ageH < 720f   -> 0.35f   // 1 month
            else          -> 0.10f
        }
    }
}
