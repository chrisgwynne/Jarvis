package com.jarvis.assistant.proactive.followup

import android.content.Context
import android.content.SharedPreferences

/**
 * LastSeenTracker — persists timestamps needed for gap-based check-ins.
 *
 * Stored in SharedPreferences (these are single scalar values; no DB needed).
 * Thread-safe via SharedPreferences apply().
 */
class LastSeenTracker(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("jarvis_lastseen", Context.MODE_PRIVATE)

    /** Call on every real user utterance received. */
    fun touchUserTurn() {
        prefs.edit().putLong(KEY_LAST_USER_TURN, System.currentTimeMillis()).apply()
    }

    /** Call after any proactive message is dispatched. */
    fun touchProactive() {
        prefs.edit().putLong(KEY_LAST_PROACTIVE, System.currentTimeMillis()).apply()
    }

    val lastUserTurnMs: Long  get() = prefs.getLong(KEY_LAST_USER_TURN, 0L)
    val lastProactiveMs: Long get() = prefs.getLong(KEY_LAST_PROACTIVE, 0L)

    /**
     * True when the user hasn't spoken since more than [thresholdMs] ago.
     * Returns false if the user has never spoken (first install) — don't nag.
     */
    fun isInactive(thresholdMs: Long = DEFAULT_INACTIVITY_MS): Boolean {
        val last = lastUserTurnMs
        if (last == 0L) return false
        return System.currentTimeMillis() - last > thresholdMs
    }

    /** True when enough time has passed since the last proactive message. */
    fun canSendProactive(minGapMs: Long = MIN_PROACTIVE_GAP_MS): Boolean =
        System.currentTimeMillis() - lastProactiveMs > minGapMs

    companion object {
        private const val KEY_LAST_USER_TURN = "last_user_turn"
        private const val KEY_LAST_PROACTIVE = "last_proactive"

        const val DEFAULT_INACTIVITY_MS   = 24L * 3_600_000   // 24 hours
        const val MIN_PROACTIVE_GAP_MS    = 6L  * 3_600_000   // 6 hours between proactives
    }
}
