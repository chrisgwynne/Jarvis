package com.jarvis.assistant.personality.template

/**
 * RecentPhraseStore — tiny in-memory ring buffer of recently-spoken
 * phrases per category.  Used by
 * [LocalResponseTemplateEngine] to:
 *
 *   - avoid speaking the same one-liner twice in a short window
 *   - throttle joke frequency when the user issues commands rapidly
 *
 * Pure / no Android dependency.  Thread-safe via copy-on-write
 * snapshots — the store is hit on every spoken local confirmation and
 * we don't want to serialize on a mutex.
 */
class RecentPhraseStore(
    /** How many recent phrases to remember per category. */
    private val capacity: Int = 8,
    /** Time-window: a phrase older than this can be reused freely. */
    private val ttlMs: Long = 5 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private data class Entry(val phrase: String, val ms: Long)

    @Volatile private var byCategory: Map<String, List<Entry>> = emptyMap()

    /** Record [phrase] under [category].  Caller is responsible for
     *  normalising the category key (e.g. tool name). */
    fun record(category: String, phrase: String) {
        if (phrase.isBlank()) return
        val now    = clock()
        val list   = byCategory[category].orEmpty().filter { now - it.ms <= ttlMs }
        val next   = (list + Entry(phrase, now)).takeLast(capacity)
        byCategory = byCategory + (category to next)
    }

    /** True iff [phrase] under [category] is in the recent window. */
    fun wasRecent(category: String, phrase: String): Boolean {
        val now = clock()
        return byCategory[category].orEmpty().any { now - it.ms <= ttlMs && it.phrase == phrase }
    }

    /** Count of distinct phrases recorded under [category] in the window. */
    fun recentCount(category: String): Int {
        val now = clock()
        return byCategory[category].orEmpty().count { now - it.ms <= ttlMs }
    }

    fun clear() { byCategory = emptyMap() }
}
