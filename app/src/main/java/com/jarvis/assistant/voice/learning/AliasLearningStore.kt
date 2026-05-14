package com.jarvis.assistant.voice.learning

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.jarvis.assistant.voice.VoiceFeatureFlags
import org.json.JSONArray
import org.json.JSONObject

/**
 * AliasLearningStore — persistent "the user corrected my mishear" memory.
 *
 * Storage is intentionally a SharedPreferences-backed JSON array so it
 * shares persistence semantics with [com.jarvis.assistant.tools.device.AppAliasStore]
 * and doesn't pull in DataStore/Room for what is fundamentally a small,
 * read-mostly list.
 *
 * Gated by [VoiceFeatureFlags.Flag.VOICE_ALIAS_LEARNING_ENABLED] — when
 * the flag is off, [record] no-ops and [lookup] returns null, so callers
 * can wire the store without committing to the behaviour yet.
 */
class AliasLearningStore(context: Context) {

    enum class Context_(val tag: String) {
        MESSAGING("messaging"),
        CONTACT  ("contact"),
        APP      ("app"),
        DEVICE   ("device"),
        ROOM     ("room"),
        GENERIC  ("generic")
    }

    data class LearnedAlias(
        val heard:    String,
        val intended: String,
        val context:  Context_,
        /** Wall-clock ms when last used — drives decay. */
        val lastUsedMs: Long,
        /** Count of times the alias has been applied since [record]. */
        val hitCount:  Int,
        /** Negative when the user explicitly rejected the alias. */
        val score:     Int
    )

    companion object {
        private const val TAG  = "AliasLearningStore"
        private const val PREF = "jarvis_alias_learning"
        private const val KEY  = "aliases"
        /** Aliases below this score are filtered out at [lookup]. */
        private const val MIN_USABLE_SCORE = -1
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Record a correction.  Idempotent on (heard, context): subsequent
     * calls bump [LearnedAlias.hitCount] and the score.
     */
    fun record(heard: String, intended: String, ctx: Context_) {
        if (!flagOn()) return
        val key = compositeKey(heard, ctx)
        val now = System.currentTimeMillis()

        val current = loadAll().toMutableMap()
        val existing = current[key]
        val updated = if (existing != null && existing.intended.equals(intended, ignoreCase = true)) {
            existing.copy(
                lastUsedMs = now,
                hitCount   = existing.hitCount + 1,
                score      = (existing.score + 1).coerceAtMost(10)
            )
        } else {
            LearnedAlias(
                heard      = heard.lowercase().trim(),
                intended   = intended.trim(),
                context    = ctx,
                lastUsedMs = now,
                hitCount   = 1,
                score      = 2
            )
        }
        current[key] = updated
        save(current.values)
        Log.d(TAG, "[ALIAS_LEARNED] heard=\"$heard\" intended=\"$intended\" ctx=${ctx.tag} score=${updated.score}")
    }

    /**
     * Lookup an alias for [heard] in [ctx].  Returns null if the flag is
     * off, the alias does not exist, or has been rejected.
     */
    fun lookup(heard: String, ctx: Context_): LearnedAlias? {
        if (!flagOn()) return null
        val map = loadAll()
        val direct = map[compositeKey(heard, ctx)]
        if (direct != null && direct.score >= MIN_USABLE_SCORE) {
            Log.d(TAG, "[ALIAS_USED] heard=\"$heard\" → \"${direct.intended}\" ctx=${ctx.tag}")
            return direct
        }
        return null
    }

    /**
     * Mark an alias as rejected (the user pushed back on a correction we
     * tried to apply).  Drops the score by 2; aliases at -1 stop being
     * returned by [lookup].
     */
    fun reject(heard: String, ctx: Context_) {
        if (!flagOn()) return
        val key = compositeKey(heard, ctx)
        val map = loadAll().toMutableMap()
        val existing = map[key] ?: return
        val updated = existing.copy(score = existing.score - 2)
        map[key] = updated
        save(map.values)
        Log.d(TAG, "[ALIAS_REJECTED] heard=\"$heard\" ctx=${ctx.tag} score=${updated.score}")
    }

    /** All non-rejected aliases for a context — used by VocabularyBiaser. */
    fun snapshot(ctx: Context_): List<LearnedAlias> =
        loadAll().values.filter { it.context == ctx && it.score >= MIN_USABLE_SCORE }

    /** Wipe everything — test/debug entry point. */
    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun flagOn() =
        VoiceFeatureFlags.isEnabled(VoiceFeatureFlags.Flag.VOICE_ALIAS_LEARNING_ENABLED)

    private fun compositeKey(heard: String, ctx: Context_) =
        "${ctx.tag}::${heard.lowercase().trim()}"

    private fun loadAll(): Map<String, LearnedAlias> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return try {
            val arr = JSONArray(raw)
            val out = LinkedHashMap<String, LearnedAlias>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ctx = Context_.values()
                    .firstOrNull { it.tag == o.optString("context") } ?: Context_.GENERIC
                val alias = LearnedAlias(
                    heard      = o.optString("heard"),
                    intended   = o.optString("intended"),
                    context    = ctx,
                    lastUsedMs = o.optLong("lastUsedMs"),
                    hitCount   = o.optInt("hitCount"),
                    score      = o.optInt("score")
                )
                out[compositeKey(alias.heard, alias.context)] = alias
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "Alias store corrupt: ${e.message} — resetting")
            emptyMap()
        }
    }

    private fun save(items: Collection<LearnedAlias>) {
        val arr = JSONArray()
        for (a in items) {
            val o = JSONObject()
                .put("heard",      a.heard)
                .put("intended",   a.intended)
                .put("context",    a.context.tag)
                .put("lastUsedMs", a.lastUsedMs)
                .put("hitCount",   a.hitCount)
                .put("score",      a.score)
            arr.put(o)
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }
}
