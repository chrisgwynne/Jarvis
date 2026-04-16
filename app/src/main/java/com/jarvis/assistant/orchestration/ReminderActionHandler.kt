package com.jarvis.assistant.orchestration

import android.util.Log
import com.jarvis.assistant.reminders.ReminderParser
import com.jarvis.assistant.reminders.ReminderRepository
import com.jarvis.assistant.reminders.db.entity.ScheduledItemType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ReminderActionHandler — executes reminder and timer [ConversationAction]s
 * and returns a spoken confirmation or error string.
 */
class ReminderActionHandler(
    private val repository: ReminderRepository
) {

    companion object {
        private const val TAG = "ReminderActionHandler"
    }

    suspend fun handleCreate(action: ConversationAction.CreateReminder): String {
        val parsed = ReminderParser.parse(action.rawInput)
            ?: return "I couldn't work out the time for that reminder. " +
                      "Try something like 'remind me in 30 minutes' or 'remind me at 3pm'."

        // Capture duration BEFORE the DB write so the confirmation reflects the requested
        // time exactly (e.g. "10 minutes", not "9 minutes 59 seconds" after insert latency).
        val durationMs = parsed.triggerAtMs - System.currentTimeMillis()

        val item = repository.create(
            label       = parsed.label,
            triggerAtMs = parsed.triggerAtMs,
            type        = ScheduledItemType.REMINDER
        )
        Log.d(TAG, "Created reminder id=${item.id} label=${parsed.label}")
        return buildConfirmation(parsed.label, parsed.triggerAtMs, durationMs, isTimer = false)
    }

    suspend fun handleTimer(action: ConversationAction.CreateTimer): String {
        val parsed = ReminderParser.parse(action.rawInput)
            ?: return "I couldn't parse that. Try 'set a timer for 10 minutes'."

        val durationMs = parsed.triggerAtMs - System.currentTimeMillis()

        val item = repository.create(
            label       = parsed.label,
            triggerAtMs = parsed.triggerAtMs,
            type        = ScheduledItemType.TIMER
        )
        Log.d(TAG, "Created timer id=${item.id} label=${parsed.label}")
        return buildConfirmation(parsed.label, parsed.triggerAtMs, durationMs, isTimer = true)
    }

    suspend fun handleList(action: ConversationAction.ListReminders): String {
        val pending = repository.getPending()
        if (pending.isEmpty()) return "You have no pending reminders or timers."

        val now = System.currentTimeMillis()
        return buildString {
            append("You have ${pending.size} pending: ")
            pending.take(3).forEachIndexed { i, item ->
                val timeStr = formatRelative(item.triggerAtMs, now)
                append("${item.label} $timeStr")
                if (i < minOf(pending.size, 3) - 1) append(", ")
            }
            if (pending.size > 3) append(", and ${pending.size - 3} more")
            append(".")
        }
    }

    suspend fun handleCancel(action: ConversationAction.CancelReminder): String {
        val pending = repository.getPending()
        if (pending.isEmpty()) return "You have no pending reminders to cancel."

        // Try to find a specific item by label; fall back to most-recently-created
        val lower  = action.rawInput.lowercase()
        val target = pending.firstOrNull { lower.contains(it.label.lowercase()) }
            ?: pending.maxByOrNull { it.createdAt }
            ?: pending.first()

        repository.cancel(target.id)
        return "Cancelled: ${target.label}."
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildConfirmation(label: String, triggerAtMs: Long, durationMs: Long, isTimer: Boolean): String {
        val word   = if (isTimer) "Timer" else "Reminder"
        // Use pre-captured durationMs (measured before DB write) so relative confirmations
        // report the requested duration exactly rather than losing a second to insert latency.
        val diffMs = durationMs

        return when {
            diffMs < 90_000L -> {
                val secs = (diffMs / 1_000L).coerceAtLeast(1L)
                "$word set for ${secs} second${if (secs == 1L) "" else "s"}."
            }
            diffMs < 3_600_000L -> {
                val mins      = diffMs / 60_000L
                val labelPart = if (label == "timer" || label == "reminder") ""
                                else " to $label"
                "$word set${labelPart} in ${mins} minute${if (mins == 1L) "" else "s"}."
            }
            diffMs < 86_400_000L -> {
                val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(triggerAtMs))
                val labelPart = if (label == "timer" || label == "reminder") ""
                                else " — $label"
                "$word set for $time$labelPart."
            }
            else -> {
                val time = SimpleDateFormat("EEE 'at' h:mm a", Locale.getDefault())
                    .format(Date(triggerAtMs))
                val labelPart = if (label == "timer" || label == "reminder") ""
                                else " — $label"
                "$word set for $time$labelPart."
            }
        }
    }

    private fun formatRelative(triggerAtMs: Long, now: Long): String {
        val diff = triggerAtMs - now
        return when {
            diff < 0            -> "overdue"
            diff < 90_000L      -> "in ${diff / 1_000}s"
            diff < 3_600_000L   -> "in ${diff / 60_000}m"
            diff < 86_400_000L  ->
                SimpleDateFormat("'at' h:mm a", Locale.getDefault()).format(Date(triggerAtMs))
            else ->
                SimpleDateFormat("EEE 'at' h:mm a", Locale.getDefault()).format(Date(triggerAtMs))
        }
    }
}
