package com.jarvis.assistant.proactive.scheduled

import java.util.Calendar
import java.util.TimeZone

/**
 * ScheduledReminderPhraseBuilder — pure-local sentence generation for
 * scheduled reminders.  No LLM round-trip; deterministic strings keep
 * the proactive surface fast, stable, and unit-testable.
 *
 * Tone follows the global voice rules (calm, direct, no alert-speak):
 *   30m → "Don't forget, $title is at $time."
 *   10m → "$title starts in 10 minutes."
 *
 * Location, when present, is appended naturally:
 *   "Don't forget, dentist is at 3 — Cardigan Health Centre."
 *
 * The hour is rendered in 12-hour conversational form ("3", "3:30",
 * "noon") so the line scans like spoken English.
 */
object ScheduledReminderPhraseBuilder {

    /**
     * @param itemTimeMs        wall-clock ms of the upstream item start
     * @param offsetMinutes     30 / 10 (other values fall back to a
     *                          neutral "in N minutes" template)
     * @param title             the item label as taken from the source
     * @param location          optional location for calendar events
     * @param timeZone          optional TZ override (tests pin this)
     */
    fun build(
        itemTimeMs: Long,
        offsetMinutes: Int,
        title: String,
        location: String? = null,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val cleanTitle = title.trim().trimEnd('.', '!', '?').ifBlank { "your reminder" }
        val timePhrase = formatTimeOfDay(itemTimeMs, timeZone)
        val tail = location?.takeIf { it.isNotBlank() }?.let { " — $it." } ?: "."
        return when (offsetMinutes) {
            30  -> "Don't forget, $cleanTitle is at $timePhrase$tail"
            10  -> "$cleanTitle starts in 10 minutes$tail"
            else -> "$cleanTitle in $offsetMinutes minutes$tail"
        }
    }

    /**
     * Render [ms] as a conversational time string in [tz].
     *   10:00 → "10", 13:30 → "1:30", 12:00 → "noon", 00:00 → "midnight"
     */
    internal fun formatTimeOfDay(ms: Long, tz: TimeZone): String {
        val cal = Calendar.getInstance(tz).apply { timeInMillis = ms }
        val h24 = cal.get(Calendar.HOUR_OF_DAY)
        val m   = cal.get(Calendar.MINUTE)
        if (h24 == 12 && m == 0) return "noon"
        if (h24 == 0  && m == 0) return "midnight"
        val h12 = when {
            h24 == 0  -> 12
            h24 > 12  -> h24 - 12
            else      -> h24
        }
        val suffix = if (h24 < 12) " a.m." else " p.m."
        return if (m == 0) "$h12$suffix" else "$h12:${"%02d".format(m)}$suffix"
    }
}
