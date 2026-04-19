package com.jarvis.assistant.tools.device

import android.content.Context
import android.util.Log
import com.jarvis.assistant.reminders.ReminderRepository
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DailyBriefingTool — surfaces a morning context string and delegates the
 * actual spoken briefing to the LLM via [ToolResult.Success.requiresLlmFollowUp].
 *
 * ## Trigger phrases
 *
 * "good morning", "morning briefing", "what's my day", "what's happening today",
 * "briefing", "daily brief"
 *
 * ## Behaviour
 *
 * 1. Resolves today's date.
 * 2. Queries [reminderRepository] for the next pending reminder (if available).
 * 3. Returns a [ToolResult.Success] whose [rawData] contains a context prompt
 *    for the LLM, and [requiresLlmFollowUp] = true so the LLM synthesises the
 *    final spoken response.
 * 4. Tracks today's date in SharedPreferences so callers can detect whether
 *    the briefing has already been delivered today.
 *
 * @param context             Application or service context.
 * @param reminderRepository  Optional — if provided, the next pending reminder is
 *                            included in the briefing context.
 */
class DailyBriefingTool(
    private val context: Context,
    private val reminderRepository: ReminderRepository? = null
) : Tool {

    override val name        = "daily_briefing"
    override val description = "Deliver a daily morning briefing including date and upcoming reminders"
    override val requiresNetwork = false

    override fun schema() = ToolSchema(
        name        = name,
        description = "Deliver a morning briefing: today's date plus the next pending reminder. The LLM synthesises the final spoken output from the returned context.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to emptyMap<String, Any>(),
            "required" to emptyList<String>()
        )
    )

    companion object {
        private const val TAG             = "DailyBriefingTool"
        private const val PREFS_NAME      = "jarvis_briefing"
        private const val KEY_LAST_DATE   = "lastBriefingDate"
        private val DATE_FORMAT           = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        private val TRIGGER_PHRASES = listOf(
            "good morning",
            "morning briefing",
            "what's my day",
            "what's happening today",
            "briefing",
            "daily brief"
        )
    }

    // ── Tool interface ────────────────────────────────────────────────────────

    override fun matches(transcript: String): ToolInput? {
        val lower = transcript.trim().lowercase()
        return if (TRIGGER_PHRASES.any { lower.contains(it) }) {
            ToolInput(transcript)
        } else {
            null
        }
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val today = DATE_FORMAT.format(Date())

        // Persist today's date so consumers can check if briefing was already done
        saveBriefingDate(today)

        // Resolve the next pending reminder
        val nextReminderSummary = resolveNextReminder()

        val contextString = buildString {
            append("Daily briefing requested.")
            append(" Date: $today.")
            append(" Next reminder: $nextReminderSummary.")
            append(" Give the user a warm good morning greeting and summarise their day based on this context.")
        }

        Log.d(TAG, "Daily briefing context: $contextString")

        return ToolResult.Success(
            spokenFeedback     = "",          // LLM follow-up produces the actual speech
            rawData            = contextString,
            requiresLlmFollowUp = true
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns a human-readable summary of the next pending reminder, or "none".
     *
     * Uses [ReminderRepository.getPending], filters to future items, and selects
     * the one with the earliest [triggerAtMs].
     */
    private suspend fun resolveNextReminder(): String {
        val repo = reminderRepository ?: return "none"
        return try {
            val now     = System.currentTimeMillis()
            val pending = repo.getPending()
            val next    = pending
                .filter { it.triggerAtMs > now }
                .minByOrNull { it.triggerAtMs }
                ?: return "none"

            val timeStr = SimpleDateFormat("h:mm a", Locale.US).format(Date(next.triggerAtMs))
            "${next.label} at $timeStr"
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve next reminder: ${e.message}")
            "none"
        }
    }

    /** Persist the date of the last briefing to SharedPreferences. */
    private fun saveBriefingDate(date: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_DATE, date)
            .apply()
    }

    /** Returns the date of the last delivered briefing, or null if never delivered. */
    fun getLastBriefingDate(): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_DATE, null)
    }
}
