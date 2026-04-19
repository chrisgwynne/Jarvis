package com.jarvis.assistant.reporting.github

import android.content.Context
import android.content.SharedPreferences

/**
 * IssueDeduper — lightweight persisted ledger keyed by fingerprint.
 *
 * Tracks, per fingerprint:
 *   * count        — total times we've seen it (bounded, resets on cooldown expiry)
 *   * firstSeenMs  — start of the current repetition window
 *   * lastFiledMs  — when the corresponding GitHub issue was created (0 if never)
 *
 * Storage is a plain SharedPreferences file.  No secrets stored here — keys are
 * 16-hex-char fingerprints and values are compact `count:firstSeen:lastFiled`
 * strings, so the file stays small and doesn't need encryption.
 */
class IssueDeduper(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    data class Snapshot(
        val count       : Int,
        val firstSeenMs : Long,
        val lastFiledMs : Long
    ) {
        companion object { val EMPTY = Snapshot(0, 0L, 0L) }
    }

    /** Returns the current snapshot for [fp] — [Snapshot.EMPTY] if never seen. */
    fun snapshot(fp: String): Snapshot {
        val raw = prefs.getString(fp, null) ?: return Snapshot.EMPTY
        val parts = raw.split(":")
        if (parts.size != 3) return Snapshot.EMPTY
        val c  = parts[0].toIntOrNull() ?: 0
        val f  = parts[1].toLongOrNull() ?: 0L
        val l  = parts[2].toLongOrNull() ?: 0L
        return Snapshot(c, f, l)
    }

    /**
     * Record that fingerprint [fp] was seen at [nowMs].  Resets the count and
     * window-start if the previous window was older than [windowMs].
     */
    fun recordSeen(fp: String, nowMs: Long, windowMs: Long): Snapshot {
        val prev = snapshot(fp)
        val (newCount, newFirstSeen) =
            if (prev.count == 0 || nowMs - prev.firstSeenMs > windowMs) 1 to nowMs
            else (prev.count + 1) to prev.firstSeenMs
        val updated = Snapshot(
            count       = newCount,
            firstSeenMs = newFirstSeen,
            lastFiledMs = prev.lastFiledMs
        )
        write(fp, updated)
        return updated
    }

    /** Record that fingerprint [fp] was actually filed as an issue at [nowMs]. */
    fun recordFiled(fp: String, nowMs: Long) {
        val prev = snapshot(fp)
        write(fp, prev.copy(lastFiledMs = nowMs))
    }

    /** Prune entries whose firstSeenMs is older than [maxAgeMs] — keeps the prefs file small. */
    fun prune(maxAgeMs: Long, nowMs: Long = System.currentTimeMillis()) {
        val editor = prefs.edit()
        var dirty = false
        for ((k, v) in prefs.all) {
            val s = (v as? String)?.split(":") ?: continue
            val firstSeen = s.getOrNull(1)?.toLongOrNull() ?: 0L
            val lastFiled = s.getOrNull(2)?.toLongOrNull() ?: 0L
            val newest = maxOf(firstSeen, lastFiled)
            if (nowMs - newest > maxAgeMs) { editor.remove(k); dirty = true }
        }
        if (dirty) editor.apply()
    }

    private fun write(fp: String, s: Snapshot) {
        prefs.edit().putString(fp, "${s.count}:${s.firstSeenMs}:${s.lastFiledMs}").apply()
    }

    companion object {
        private const val PREFS_FILE = "jarvis_issue_deduper"
    }
}
