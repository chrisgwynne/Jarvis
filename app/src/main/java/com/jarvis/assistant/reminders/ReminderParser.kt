package com.jarvis.assistant.reminders

import java.util.Calendar

/**
 * ReminderParser — converts natural-language time expressions into an
 * absolute epoch-ms trigger time.
 *
 * Supported forms:
 *   • "in 5 minutes" / "in 2 hours" / "in 90 seconds"
 *   • "at 3pm" / "at 3:30 pm" / "at 15:30"
 *   • "tomorrow at 9am"
 *   • "remind me in 30 minutes to take my medication"
 *   • "set a timer for 10 minutes"
 *
 * Pure Kotlin — no external libraries, no Android dependencies.
 */
object ReminderParser {

    data class ParsedTime(
        val triggerAtMs: Long,
        val label: String,
        val isTimer: Boolean
    )

    // ── Patterns ──────────────────────────────────────────────────────────────

    private val IN_RELATIVE = Regex(
        """(?:in|for)\s+(\d+(?:\.\d+)?)\s+(second|minute|hour|sec|min|hr)s?""",
        RegexOption.IGNORE_CASE
    )

    private val AT_CLOCK = Regex(
        """(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)""",
        RegexOption.IGNORE_CASE
    )

    private val AT_24H = Regex(
        """at\s+(\d{2}):(\d{2})(?!\s*[ap]m)"""
    )

    private val TOMORROW = Regex("""tomorrow""", RegexOption.IGNORE_CASE)

    // ── Public API ────────────────────────────────────────────────────────────

    fun parse(input: String): ParsedTime? {
        val lower = input.lowercase().trim()
        val isTimer = isTimerRequest(lower)
        val label = extractLabel(lower, isTimer)

        // "in X minutes/hours/seconds"
        IN_RELATIVE.find(lower)?.let { m ->
            val amount = m.groupValues[1].toDouble()
            val unit   = m.groupValues[2].lowercase()
            val ms: Long = when {
                unit.startsWith("s") -> (amount * 1_000).toLong()
                unit.startsWith("m") -> (amount * 60_000).toLong()
                unit.startsWith("h") -> (amount * 3_600_000).toLong()
                else -> return null
            }
            return ParsedTime(System.currentTimeMillis() + ms, label, isTimer)
        }

        val isTomorrow = TOMORROW.containsMatchIn(lower)

        // "at HH:MM am/pm"
        AT_CLOCK.find(lower)?.let { m ->
            return buildClockTime(
                hour   = m.groupValues[1].toIntOrNull() ?: return null,
                minute = m.groupValues[2].toIntOrNull() ?: 0,
                ampm   = m.groupValues[3].lowercase(),
                isTomorrow = isTomorrow,
                label  = label,
                isTimer = isTimer
            )
        }

        // "at HH:MM" (24-hour, no am/pm)
        AT_24H.find(lower)?.let { m ->
            return buildClockTime(
                hour   = m.groupValues[1].toIntOrNull() ?: return null,
                minute = m.groupValues[2].toIntOrNull() ?: 0,
                ampm   = "",
                isTomorrow = isTomorrow,
                label  = label,
                isTimer = isTimer
            )
        }

        return null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildClockTime(
        hour: Int,
        minute: Int,
        ampm: String,
        isTomorrow: Boolean,
        label: String,
        isTimer: Boolean
    ): ParsedTime {
        var h = hour
        if (ampm == "pm" && h < 12) h += 12
        if (ampm == "am" && h == 12) h = 0

        val cal = Calendar.getInstance()
        if (isTomorrow) cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, h)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // If the parsed time is already in the past today, push to tomorrow
        if (!isTomorrow && cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        return ParsedTime(cal.timeInMillis, label, isTimer)
    }

    private fun isTimerRequest(lower: String): Boolean =
        lower.contains("timer") ||
        lower.contains("countdown") ||
        lower.contains("count down")

    /**
     * Extract a human-readable label from the utterance, stripping out
     * the command boilerplate and time expression so only the "what" remains.
     */
    private fun extractLabel(lower: String, isTimer: Boolean): String {
        if (isTimer) {
            val afterFor = lower.substringAfter(" for ", "")
            val cleaned  = IN_RELATIVE.replace(afterFor, "").trim().trimEnd(',', '.')
            return cleaned.ifBlank { "timer" }
        }

        var label = lower
            .removePrefix("please ")
            .let {
                listOf(
                    "remind me to ", "remind me ", "reminder to ", "reminder ",
                    "set a reminder to ", "set a reminder for ", "set reminder to ",
                    "create a reminder to ", "add a reminder to "
                ).fold(it) { s, prefix -> s.removePrefix(prefix) }
            }

        // Strip the time expression (relative or clock)
        label = IN_RELATIVE.replace(label, "")
        label = AT_CLOCK.replace(label, "")
        label = AT_24H.replace(label, "")
        label = TOMORROW.replace(label, "")

        // Strip residual connecting words
        label = Regex("""^(to |about |that |at |in |for |on )+""").replace(label, "")
        label = label.trim().trimEnd(',', '.')

        return label.ifBlank { "reminder" }
    }
}
