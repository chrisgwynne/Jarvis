package com.jarvis.assistant.core.presence

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * ConversationThreads — a live rolling set of topics the user has raised
 * that haven't yet faded out. Fed by every user turn so the system prompt
 * can tell the LLM what's still open, recently closed, or faded.
 *
 * Lifecycle:
 *   ACTIVE   — touched within [activeWindowMs] (default 15 min).
 *   DORMANT  — last touched [activeWindowMs] → [dormantWindowMs] (default 60 min).
 *              Still worth referencing if the user circles back; not worth
 *              volunteering.
 *   CLOSED   — untouched for > [dormantWindowMs]. Dropped from the store.
 *
 * Matching is keyword-based, deliberately dumb: any existing non-closed
 * thread whose keyword set intersects the new turn's extracted terms is
 * "touched" (lastTouchedMs updated). If nothing matches, a new thread is
 * opened. This is cheap enough to run on every turn; LLM-based topic
 * extraction is a future refinement.
 *
 * Persistence: SharedPreferences, JSON-encoded. Small payload, rare writes,
 * no Room schema churn. Tolerates malformed reads by starting empty.
 */
class ConversationThreads(
    context: Context,
    private val activeWindowMs: Long = 15 * 60 * 1000L,
    private val dormantWindowMs: Long = 60 * 60 * 1000L,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    enum class Status { ACTIVE, DORMANT }

    data class Thread(
        val id: String,
        val topic: String,
        val keywords: Set<String>,
        val openedAtMs: Long,
        val lastTouchedMs: Long,
    )

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val threads = ConcurrentHashMap<String, Thread>()
    private val recentlyClosed = ArrayDeque<String>()

    init { hydrate() }

    /**
     * Extract keywords from [utterance] and either touch the best matching
     * existing thread or open a new one. Returns the affected thread.
     */
    fun touchFromUtterance(utterance: String): Thread? {
        val terms = extractKeywords(utterance)
        if (terms.isEmpty()) return null
        val thread = touch(terms, topic = terms.firstOrNull())
        return thread
    }

    fun touch(keywords: Iterable<String>, topic: String? = null): Thread {
        val now = nowMs()
        sweep(now)
        val set = keywords.map { it.lowercase() }.filter { it.length >= 3 }.toSet()
        val match = threads.values.firstOrNull { existing ->
            existing.keywords.any { it in set }
        }
        val updated = if (match != null) {
            match.copy(lastTouchedMs = now, keywords = match.keywords + set)
        } else {
            val id = "t_${now}_${set.hashCode().toUInt().toString(16)}"
            Thread(
                id = id,
                topic = topic?.takeIf { it.isNotBlank() } ?: set.first(),
                keywords = set,
                openedAtMs = now,
                lastTouchedMs = now,
            )
        }
        threads[updated.id] = updated
        persist()
        return updated
    }

    fun close(threadId: String) {
        val removed = threads.remove(threadId)
        if (removed != null) {
            recentlyClosed.addLast(removed.topic)
            while (recentlyClosed.size > 5) recentlyClosed.removeFirst()
            persist()
        }
    }

    fun sweep(now: Long = nowMs()) {
        val cutoff = now - dormantWindowMs
        val expired = threads.values.filter { it.lastTouchedMs < cutoff }
        expired.forEach { close(it.id) }
    }

    fun statusOf(thread: Thread, now: Long = nowMs()): Status {
        val age = now - thread.lastTouchedMs
        return if (age <= activeWindowMs) Status.ACTIVE else Status.DORMANT
    }

    fun active(): List<Thread> {
        val now = nowMs()
        sweep(now)
        return threads.values.filter { statusOf(it, now) == Status.ACTIVE }
            .sortedByDescending { it.lastTouchedMs }
    }

    fun dormant(): List<Thread> {
        val now = nowMs()
        sweep(now)
        return threads.values.filter { statusOf(it, now) == Status.DORMANT }
            .sortedByDescending { it.lastTouchedMs }
    }

    fun recentlyClosed(): List<String> = recentlyClosed.toList()

    /**
     * One-liner fragment for the system prompt — deliberately short so it
     * doesn't dominate the context window. Returns empty string when there
     * is nothing worth saying, so callers can append unconditionally.
     */
    fun toPromptFragment(): String {
        val now = nowMs()
        sweep(now)
        val active = threads.values.filter { statusOf(it, now) == Status.ACTIVE }
            .sortedByDescending { it.lastTouchedMs }
            .take(4)
            .map { it.topic }
        val dormant = threads.values.filter { statusOf(it, now) == Status.DORMANT }
            .sortedByDescending { it.lastTouchedMs }
            .take(3)
            .map { it.topic }
        if (active.isEmpty() && dormant.isEmpty()) return ""
        return buildString {
            append("[Conversation threads — never cite literally.")
            if (active.isNotEmpty()) append(" Still open: ").append(active.joinToString(", ")).append('.')
            if (dormant.isNotEmpty()) append(" Faded: ").append(dormant.joinToString(", ")).append('.')
            append(" Don't reopen faded threads unless the user does first.]")
        }
    }

    fun clearAll() {
        threads.clear()
        recentlyClosed.clear()
        prefs.edit().clear().apply()
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun hydrate() {
        val raw = prefs.getString(KEY_THREADS, null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val kws = o.getJSONArray("k")
                val set = buildSet<String> { for (j in 0 until kws.length()) add(kws.getString(j)) }
                val t = Thread(
                    id = o.getString("id"),
                    topic = o.getString("t"),
                    keywords = set,
                    openedAtMs = o.getLong("o"),
                    lastTouchedMs = o.getLong("l"),
                )
                threads[t.id] = t
            }
        } catch (_: Exception) { /* tolerate malformed */ }
        val closed = prefs.getString(KEY_CLOSED, null) ?: return
        try {
            val arr = JSONArray(closed)
            for (i in 0 until arr.length()) recentlyClosed.addLast(arr.getString(i))
        } catch (_: Exception) { /* ignore */ }
    }

    private fun persist() {
        val arr = JSONArray()
        for (t in threads.values) {
            val kws = JSONArray().apply { t.keywords.forEach { put(it) } }
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("t", t.topic)
                    .put("k", kws)
                    .put("o", t.openedAtMs)
                    .put("l", t.lastTouchedMs)
            )
        }
        val closed = JSONArray().apply { recentlyClosed.forEach { put(it) } }
        prefs.edit()
            .putString(KEY_THREADS, arr.toString())
            .putString(KEY_CLOSED, closed.toString())
            .apply()
    }

    // ── Keyword extraction ───────────────────────────────────────────────────

    private fun extractKeywords(utterance: String): Set<String> =
        utterance
            .split(Regex("""[^\p{L}\p{N}]+"""))
            .map { it.lowercase() }
            .filter { it.length >= 3 && it !in STOPWORDS }
            .toSet()

    companion object {
        private const val PREFS = "jarvis_conversation_threads"
        private const val KEY_THREADS = "threads"
        private const val KEY_CLOSED = "recently_closed"
        private val STOPWORDS = setOf(
            "the", "and", "for", "with", "that", "this", "you", "your", "have",
            "has", "had", "not", "but", "can", "could", "would", "should", "will",
            "are", "was", "were", "been", "being", "is", "am", "be", "what", "which",
            "who", "when", "where", "why", "how", "from", "into", "about", "out", "off",
            "any", "all", "some", "more", "less", "very", "just", "like", "get", "got",
            "jarvis",
        )
    }
}
