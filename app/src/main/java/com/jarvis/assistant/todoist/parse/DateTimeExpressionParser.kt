package com.jarvis.assistant.todoist.parse

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * DateTimeExpressionParser — extract natural-language date / time / recurrence
 * tokens from a transcript.
 *
 * Strictly local — no LLM, no OpenClaw.  Produces structured output the
 * Todoist API can consume directly via its `due.string` field (recurrence)
 * OR via the structured `date` / `datetime` fields when we can resolve
 * concrete values locally.
 *
 * Three flavours of output, returned together:
 *
 *  - [Parsed.date]       — "YYYY-MM-DD" when we resolved a concrete date.
 *  - [Parsed.time]       — "HH:mm" 24-hour, when we resolved a concrete time.
 *  - [Parsed.naturalString] — the original natural form we extracted ("every
 *                              monday at 9am") for Todoist's `due.string`.
 *  - [Parsed.isRecurring] — true when the expression names a recurrence
 *                            ("every monday", "every weekday", "daily at 8").
 *  - [Parsed.consumedRange] — character range in the source that the
 *                              expression occupied, so the caller can strip
 *                              it from the task content.
 *
 * The parser deliberately stays cheap: order-of-pattern matching, no
 * back-tracking magic.  It misses unusual phrasings rather than guessing.
 */
object DateTimeExpressionParser {

    data class Parsed(
        val date: String? = null,
        val time: String? = null,
        val naturalString: String? = null,
        val isRecurring: Boolean = false,
        val consumedRange: IntRange? = null,
    ) {
        val isEmpty: Boolean get() =
            date == null && time == null && naturalString == null
    }

    // ── Recurrence ────────────────────────────────────────────────────────

    private val RECURRENCE_RX = Regex(
        """(?ix)
        \b(
            every\s+(?:day|morning|afternoon|evening|night|weekday|weekend)
              (?:\s+at\s+\d{1,2}(?::\d{2})?\s*(?:am|pm)?)?
          | every\s+\d+\s+(?:minutes?|hours?|days?|weeks?|months?|years?)
          | every\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)
              (?:\s+at\s+\d{1,2}(?::\d{2})?\s*(?:am|pm)?)?
          | every\s+(?:other\s+)?week
          | every\s+month
          | every\s+year
          | daily(?:\s+at\s+\d{1,2}(?::\d{2})?\s*(?:am|pm)?)?
          | weekly
          | monthly
          | yearly
        )\b
        """,
    )

    // ── Days / relative dates ─────────────────────────────────────────────

    /**
     * Relative day expressions.  Covers:
     *   - today / tonight
     *   - tomorrow [morning|afternoon|evening|night]
     *   - the day after tomorrow
     *   - next/this <weekday>
     *   - this weekend
     *   - bare <weekday> ("Friday")
     *   - later / later today
     *
     * Order of alternatives matters: longer / more specific forms come
     * first so the regex engine doesn't lock onto a shorter prefix.
     */
    private val RELATIVE_DAY_RX = Regex(
        """(?ix)
        \b(
            the\s+day\s+after\s+tomorrow
          | tomorrow(?:\s+(?:morning|afternoon|evening|night))?
          | (?:next|this)\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)
          | this\s+weekend
          | (?:later\s+today|later\s+this\s+(?:morning|afternoon|evening))
          | later
          | today
          | tonight
          | this\s+(?:morning|afternoon|evening|weekend)
          | \b(?:on\s+)?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)
        )\b
        """,
    )

    /**
     * Default minute-of-day for under-specified relative expressions.
     * Local-only so callers don't need to pass app settings into the
     * parser; the router can re-resolve from settings if needed.
     */
    private const val DEFAULT_EVENING_MINUTE = 20 * 60   // 20:00
    private const val DEFAULT_LATER_MINUTE   = 60        // +60 min from now
    private const val DEFAULT_MORNING_MINUTE = 9  * 60   // 09:00
    private const val DEFAULT_AFTERNOON_MINUTE = 14 * 60 // 14:00

    private val IN_N_UNITS_RX = Regex(
        """(?i)\bin\s+(\d+)\s+(minute|minutes|min|hour|hours|hr|hrs|day|days|week|weeks|month|months)\b""",
    )

    private val EXPLICIT_DATE_RX = Regex(
        """(?ix)
        \b(?:on\s+)?
        (?:
            (\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*
          | (jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\s+(\d{1,2})(?:st|nd|rd|th)?
        )
        (?:\s*,?\s*(\d{4}))?
        \b
        """,
    )

    // ── Times ─────────────────────────────────────────────────────────────

    /** Capture group 1 = hour, 2 = minute (optional), 3 = am/pm (optional). */
    private val EXPLICIT_TIME_RX = Regex(
        """(?ix)
        \bat\s+
        (\d{1,2})
        (?: : (\d{2}) )?
        \s*
        (am|pm|a\.m\.|p\.m\.)?
        \b
        """,
    )

    /** "tomorrow at 7" — fragment "at 7" with no am/pm; assume evening default. */
    private val LOOSE_HOUR_RX = Regex(
        """(?ix)
        \bat\s+
        (\d{1,2})
        (?!:)
        \b
        (?!\s*(?:am|pm|a\.m\.|p\.m\.))
        """,
    )

    /**
     * Top-level entry point.  Pass the lowercased transcript so the regexes
     * stay simple.  Returns the FIRST date/time/recurrence we recognise —
     * "tomorrow at 7" wins; we don't try to fuse multiple distant tokens.
     */
    fun parse(
        lower: String,
        nowMs: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
    ): Parsed {
        if (lower.isBlank()) return Parsed()

        // 1. Recurrence first.  When present it usually subsumes the date.
        RECURRENCE_RX.find(lower)?.let { m ->
            return Parsed(
                naturalString = m.value.trim(),
                isRecurring   = true,
                consumedRange = m.range,
            )
        }

        // 2. Relative day ("today", "tomorrow", "next monday").  Combine
        //    with an explicit/loose time if present.
        val cal = Calendar.getInstance(zone, Locale.UK).apply { timeInMillis = nowMs }
        val relDay = RELATIVE_DAY_RX.find(lower)
        val date: String?
        var impliedTime: String? = null
        val naturalSoFar = StringBuilder()
        var consumed: IntRange? = null
        if (relDay != null) {
            val resolved = resolveRelativeDayWithTime(relDay.value, cal, nowMs, zone)
            date = resolved.first
            impliedTime = resolved.second
            naturalSoFar.append(relDay.value)
            consumed = relDay.range
        } else {
            // 3. "in N units" — quick numeric offset.
            val inN = IN_N_UNITS_RX.find(lower)
            if (inN != null) {
                date = applyOffsetAndFormatDate(cal, inN.groupValues[1].toInt(), inN.groupValues[2])
                naturalSoFar.append(inN.value)
                consumed = inN.range
                // For "in N minutes" / "in N hours" we also produced a time;
                // compute it from the same offset.
                val time = applyOffsetAndFormatTime(nowMs, zone, inN.groupValues[1].toInt(), inN.groupValues[2])
                return Parsed(
                    date          = date,
                    time          = time,
                    naturalString = naturalSoFar.toString(),
                    consumedRange = consumed,
                )
            } else {
                // 4. Explicit calendar date ("july 12", "12th july", "12 july 2026").
                val cd = EXPLICIT_DATE_RX.find(lower)
                if (cd != null) {
                    date = resolveExplicitDate(cd, cal)
                    naturalSoFar.append(cd.value)
                    consumed = cd.range
                } else {
                    date = null
                }
            }
        }

        // 5. Time (combines with either a relative or explicit date, or
        //    stands alone — "at 7pm" with no date defaults to today/tomorrow).
        //    impliedTime from a "tonight"/"this evening"/"tomorrow morning"
        //    style relative expression is used as a fallback only — an
        //    explicit "at HH" still wins.
        val tm = EXPLICIT_TIME_RX.find(lower)
        val time: String?
        if (tm != null) {
            val hour    = tm.groupValues[1].toIntOrNull() ?: 0
            val minute  = tm.groupValues[2].toIntOrNull() ?: 0
            val ampmRaw = tm.groupValues[3].lowercase()
            // Smart AM/PM inference for bare "at N":
            //   - Explicit am/pm wins.
            //   - "morning"/"am" in the wider utterance → AM.
            //   - "evening"/"tonight"/"pm"/"night"      → PM.
            //   - Otherwise: hours 1–7 default to PM (reminders skew
            //     evening), 8–11 stay AM (working hours), 12 stays as-is.
            //   Tested examples:
            //     "tomorrow at 7"       → 19:00 (smart PM)
            //     "tomorrow at 9"       → 09:00 (working hours)
            //     "tomorrow at 8am"     → 08:00 (explicit)
            //     "tomorrow morning 7"  → 07:00 (morning hint)
            val hasMorningHint = lower.contains("morning") ||
                lower.contains(Regex("""\bam\b"""))
            val hasEveningHint = lower.contains("evening") ||
                lower.contains("tonight")  ||
                lower.contains(" night")   ||
                lower.contains("pm")
            val ampm = when {
                ampmRaw.isNotBlank()                                 -> ampmRaw
                hasEveningHint && hour in 1..11                      -> "pm"
                hasMorningHint                                       -> "am"
                hour in 1..7                                         -> "pm"   // smart default
                else                                                 -> ampmRaw
            }
            time = format24h(hour, minute, ampm)
            if (naturalSoFar.isNotEmpty()) naturalSoFar.append(" ")
            naturalSoFar.append(tm.value)
            consumed = mergeRange(consumed, tm.range)
        } else {
            val loose = LOOSE_HOUR_RX.find(lower)
            if (loose != null) {
                // "tomorrow at 7" — pick PM when paired with an evening word
                // / tomorrow / today, else default to PM only when hour < 12.
                val hour = loose.groupValues[1].toIntOrNull() ?: 0
                val implicitPm = hour in 1..11 && (
                    lower.contains("evening") ||
                    lower.contains("tonight")  ||
                    lower.contains("pm")
                )
                val ampm = if (implicitPm) "pm" else ""
                time = format24h(hour, 0, ampm)
                if (naturalSoFar.isNotEmpty()) naturalSoFar.append(" ")
                naturalSoFar.append(loose.value)
                consumed = mergeRange(consumed, loose.range)
            } else {
                time = null
            }
        }

        return Parsed(
            date          = date,
            time          = time ?: impliedTime,
            naturalString = naturalSoFar.toString().takeIf { it.isNotBlank() },
            consumedRange = consumed,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Resolve a relative-day token into (date, impliedTime?).  The
     * implied-time component lets phrases like "tonight" carry a
     * sensible default (20:00) so the router doesn't have to ask
     * "what time?" — see the audit spec.
     */
    private fun resolveRelativeDayWithTime(
        token: String, cal: Calendar, nowMs: Long, zone: TimeZone,
    ): Pair<String, String?> {
        val t = token.lowercase().trim()
        val c = cal.clone() as Calendar
        var implied: String? = null
        when {
            t == "today"                       -> { /* same day */ }
            t == "tonight" ||
                t == "this evening" ||
                t == "this night"              -> implied = formatMinute(DEFAULT_EVENING_MINUTE)
            t == "this morning"                -> implied = formatMinute(DEFAULT_MORNING_MINUTE)
            t == "this afternoon"              -> implied = formatMinute(DEFAULT_AFTERNOON_MINUTE)
            t.startsWith("tomorrow") -> {
                c.add(Calendar.DAY_OF_MONTH, 1)
                implied = when {
                    t.endsWith("morning")   -> formatMinute(DEFAULT_MORNING_MINUTE)
                    t.endsWith("afternoon") -> formatMinute(DEFAULT_AFTERNOON_MINUTE)
                    t.endsWith("evening") ||
                        t.endsWith("night") -> formatMinute(DEFAULT_EVENING_MINUTE)
                    else                    -> null
                }
            }
            t.startsWith("the day after tomorrow") -> c.add(Calendar.DAY_OF_MONTH, 2)
            t == "this weekend" -> advanceToWeekday(c, Calendar.SATURDAY)
            t.startsWith("next ") -> {
                val day = t.removePrefix("next ").trim()
                advanceToWeekday(c, parseDayOfWeek(day))
            }
            t.startsWith("this ") -> {
                val day = t.removePrefix("this ").trim()
                advanceToWeekday(c, parseDayOfWeek(day), includeToday = true)
            }
            t == "later" || t == "later today" -> {
                // +60 minutes — same date in nearly every case, but we
                // also return the implied time so the time slot is filled.
                val c2 = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
                c2.add(Calendar.MINUTE, DEFAULT_LATER_MINUTE)
                c.timeInMillis = c2.timeInMillis
                implied = "%02d:%02d".format(c2.get(Calendar.HOUR_OF_DAY), c2.get(Calendar.MINUTE))
            }
            t.startsWith("later this ") -> {
                implied = when {
                    t.endsWith("morning")   -> formatMinute(DEFAULT_MORNING_MINUTE)
                    t.endsWith("afternoon") -> formatMinute(DEFAULT_AFTERNOON_MINUTE)
                    t.endsWith("evening")   -> formatMinute(DEFAULT_EVENING_MINUTE)
                    else                    -> null
                }
            }
            else -> {
                // Bare weekday: "friday", "on friday".
                val dayName = t.removePrefix("on ").trim()
                val target  = parseDayOfWeek(dayName)
                advanceToWeekday(c, target)
            }
        }
        return formatDate(c) to implied
    }

    private fun formatMinute(min: Int): String =
        "%02d:%02d".format(min / 60, min % 60)

    private fun parseDayOfWeek(s: String): Int = when (s) {
        "sunday"    -> Calendar.SUNDAY
        "monday"    -> Calendar.MONDAY
        "tuesday"   -> Calendar.TUESDAY
        "wednesday" -> Calendar.WEDNESDAY
        "thursday"  -> Calendar.THURSDAY
        "friday"    -> Calendar.FRIDAY
        "saturday"  -> Calendar.SATURDAY
        else        -> Calendar.MONDAY
    }

    /**
     * Advance [c] to the next occurrence of [target].  When
     * [includeToday] is true (the "this <day>" form) a same-day match
     * stays put rather than rolling forward a week.  Otherwise "next
     * monday" is always the upcoming Monday strictly after today.
     */
    private fun advanceToWeekday(c: Calendar, target: Int, includeToday: Boolean = false) {
        var add = (target - c.get(Calendar.DAY_OF_WEEK) + 7) % 7
        if (add == 0 && !includeToday) add = 7
        c.add(Calendar.DAY_OF_MONTH, add)
    }

    private fun resolveExplicitDate(m: MatchResult, cal: Calendar): String? {
        // Either form-A "(\d+) (mon)" or form-B "(mon) (\d+)".
        val groups = m.groupValues
        val day: Int
        val monStr: String
        val year: Int?
        when {
            groups[1].isNotEmpty() -> { day = groups[1].toInt(); monStr = groups[2]; year = groups[5].toIntOrNull() }
            else                   -> { day = groups[4].toInt(); monStr = groups[3]; year = groups[5].toIntOrNull() }
        }
        val month = monthIndex(monStr) ?: return null
        val c = cal.clone() as Calendar
        c.set(Calendar.MONTH, month)
        c.set(Calendar.DAY_OF_MONTH, day.coerceIn(1, 31))
        if (year != null) c.set(Calendar.YEAR, year)
        else if (c.timeInMillis < cal.timeInMillis) c.add(Calendar.YEAR, 1)  // roll forward
        return formatDate(c)
    }

    private fun monthIndex(s: String): Int? = when (s.lowercase().take(3)) {
        "jan" -> 0; "feb" -> 1; "mar" -> 2; "apr" -> 3
        "may" -> 4; "jun" -> 5; "jul" -> 6; "aug" -> 7
        "sep" -> 8; "oct" -> 9; "nov" -> 10; "dec" -> 11
        else  -> null
    }

    private fun applyOffsetAndFormatDate(
        cal: Calendar, n: Int, unit: String,
    ): String {
        val c = cal.clone() as Calendar
        when {
            unit.startsWith("min")  -> c.add(Calendar.MINUTE, n)
            unit.startsWith("hour") || unit == "hr" || unit == "hrs" -> c.add(Calendar.HOUR_OF_DAY, n)
            unit.startsWith("day")  -> c.add(Calendar.DAY_OF_MONTH, n)
            unit.startsWith("week") -> c.add(Calendar.WEEK_OF_YEAR, n)
            unit.startsWith("month")-> c.add(Calendar.MONTH, n)
        }
        return formatDate(c)
    }

    private fun applyOffsetAndFormatTime(
        nowMs: Long, zone: TimeZone, n: Int, unit: String,
    ): String? {
        if (!(unit.startsWith("min") || unit.startsWith("hour") || unit == "hr" || unit == "hrs")) {
            return null
        }
        val c = Calendar.getInstance(zone, Locale.UK).apply { timeInMillis = nowMs }
        if (unit.startsWith("min")) c.add(Calendar.MINUTE, n)
        else                        c.add(Calendar.HOUR_OF_DAY, n)
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    private fun format24h(rawHour: Int, minute: Int, ampm: String): String {
        var h = rawHour
        when (ampm.replace(".", "")) {
            "pm" -> if (h < 12) h += 12
            "am" -> if (h == 12) h = 0
            else -> { /* leave as-is */ }
        }
        return "%02d:%02d".format(h.coerceIn(0, 23), minute.coerceIn(0, 59))
    }

    private fun formatDate(c: Calendar): String {
        val y = c.get(Calendar.YEAR)
        val m = c.get(Calendar.MONTH) + 1
        val d = c.get(Calendar.DAY_OF_MONTH)
        return "%04d-%02d-%02d".format(y, m, d)
    }

    private fun mergeRange(a: IntRange?, b: IntRange?): IntRange? {
        if (a == null) return b
        if (b == null) return a
        return minOf(a.first, b.first)..maxOf(a.last, b.last)
    }
}
