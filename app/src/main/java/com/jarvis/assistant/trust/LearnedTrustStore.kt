package com.jarvis.assistant.trust

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Persists per-tool trust learning so Jarvis adapts confirmation frequency
 * over time without any server component.
 *
 * Learning signals:
 *  - [recordApproval] — called every time a tool executes (auto or confirmed)
 *  - [recordUserFeedback] — called when the user explicitly says
 *    "stop asking me that" or "always ask before sending"
 *
 * Decision: [shouldSkipConfirmation] returns true when evidence strongly
 * suggests the user never wants a confirmation for this tool.
 *
 * Storage: plain SharedPreferences (non-encrypted; no secrets stored here).
 */
class LearnedTrustStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("jarvis_learned_trust", Context.MODE_PRIVATE)

    private companion object {
        private const val TAG = "LearnedTrustStore"

        // Keys
        private fun autoKey(tool: String)      = "auto_$tool"
        private fun confirmedKey(tool: String) = "conf_$tool"
        private fun feedbackKey(tool: String)  = "fb_$tool"   // "never" | "always" | ""

        /** After this many consecutive auto-approvals, we trust the pattern. */
        private const val AUTO_APPROVE_STREAK = 5

        /** Number of recent executions to track per tool. */
        private const val WINDOW = 20
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    /** Call whenever a tool executes without a confirmation prompt. */
    fun recordAutoApproval(toolName: String) {
        val key = autoKey(toolName)
        val count = prefs.getInt(key, 0) + 1
        prefs.edit().putInt(key, count).apply()
        Log.d(TAG, "[LEARNED] auto=$count tool=$toolName")
    }

    /** Call whenever the user explicitly confirmed an action (said "yes"). */
    fun recordConfirmedApproval(toolName: String) {
        val key = confirmedKey(toolName)
        val count = prefs.getInt(key, 0) + 1
        prefs.edit().putInt(key, minOf(count, WINDOW)).apply()
    }

    /**
     * Call when the user explicitly says "stop asking me that" (→ [UserFeedback.SKIP])
     * or "always ask before sending" (→ [UserFeedback.ALWAYS_ASK]).
     */
    fun recordUserFeedback(toolName: String, feedback: UserFeedback) {
        prefs.edit().putString(feedbackKey(toolName), feedback.name).apply()
        Log.d(TAG, "[LEARNED] user feedback=$feedback tool=$toolName")
    }

    // ── Decision ──────────────────────────────────────────────────────────────

    /**
     * Returns true if we have enough evidence that confirmations for [toolName]
     * should be skipped.
     *
     * Rules (in priority order):
     *  1. Explicit "always ask" feedback → false
     *  2. Explicit "skip" feedback → true
     *  3. Tool has auto-approved ≥ AUTO_APPROVE_STREAK times with no corrections → true
     */
    fun shouldSkipConfirmation(toolName: String): Boolean {
        val fb = prefs.getString(feedbackKey(toolName), "") ?: ""
        if (fb == UserFeedback.ALWAYS_ASK.name) return false
        if (fb == UserFeedback.SKIP.name)       return true

        val autoCount = prefs.getInt(autoKey(toolName), 0)
        return autoCount >= AUTO_APPROVE_STREAK
    }

    /**
     * Returns true if the user explicitly requested "always ask" for [toolName].
     */
    fun requiresConfirmation(toolName: String): Boolean {
        val fb = prefs.getString(feedbackKey(toolName), "") ?: ""
        return fb == UserFeedback.ALWAYS_ASK.name
    }

    /** Clear learned data for a specific tool (used from diagnostics / reset). */
    fun clearTool(toolName: String) {
        prefs.edit()
            .remove(autoKey(toolName))
            .remove(confirmedKey(toolName))
            .remove(feedbackKey(toolName))
            .apply()
    }

    /** Wipe all learned trust data. */
    fun clearAll() {
        prefs.edit().clear().apply()
        Log.i(TAG, "[LEARNED] all trust data cleared")
    }

    /** Snapshot of per-tool stats for the diagnostics screen. */
    fun snapshot(): Map<String, ToolTrustStats> {
        val all = prefs.all
        val tools = all.keys
            .mapNotNull { key ->
                when {
                    key.startsWith("auto_")  -> key.removePrefix("auto_")
                    key.startsWith("conf_")  -> key.removePrefix("conf_")
                    key.startsWith("fb_")    -> key.removePrefix("fb_")
                    else                     -> null
                }
            }
            .toSet()

        return tools.associateWith { tool ->
            ToolTrustStats(
                autoApprovals = prefs.getInt(autoKey(tool), 0),
                confirmedApprovals = prefs.getInt(confirmedKey(tool), 0),
                userFeedback = runCatching {
                    prefs.getString(feedbackKey(tool), null)?.let { UserFeedback.valueOf(it) }
                }.getOrNull()
            )
        }
    }
}

enum class UserFeedback {
    /** "Stop asking me that" — skip confirmation for this tool. */
    SKIP,
    /** "Always ask before sending" — always confirm for this tool. */
    ALWAYS_ASK,
}

data class ToolTrustStats(
    val autoApprovals: Int,
    val confirmedApprovals: Int,
    val userFeedback: UserFeedback?,
)
