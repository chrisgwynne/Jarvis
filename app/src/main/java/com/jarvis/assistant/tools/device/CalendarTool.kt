package com.jarvis.assistant.tools.device

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import com.jarvis.assistant.reminders.ReminderParser
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * CalendarTool — reads events from the Android CalendarContract content provider.
 *
 * No Google OAuth required; reads whatever calendars are synced to the device
 * (Google Calendar, Exchange, etc.) via the system content provider.
 *
 * Supported queries:
 *   - today / what's today / what have I got today / today's events / my schedule
 *   - tomorrow / tomorrow's schedule / what do I have tomorrow
 *   - this week / my week / what's this week
 *   - next appointment / next meeting / when's my next
 */
class CalendarTool(private val context: Context) : Tool {

    override val name = "calendar"
    override val description = "Read and write calendar events on the device"
    override val requiresNetwork = false
    override val requiredPermissions = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    override fun schema() = ToolSchema(
        name        = name,
        description = "Read or create calendar events. Use action=read for queries, action=create to add events.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "action" to mapOf("type" to "string", "enum" to listOf("read", "create"), "description" to "read = query events, create = add a new event"),
                "period" to mapOf("type" to "string", "enum" to listOf("today", "tomorrow", "week", "next"), "description" to "Time period for read queries"),
                "title"  to mapOf("type" to "string", "description" to "Event title for create"),
                "when"   to mapOf("type" to "string", "description" to "Natural language time expression, e.g. Friday at 3pm")
            ),
            "required" to listOf("action")
        )
    )

    companion object {
        private const val TAG = "CalendarTool"
        private const val EVENT_LIMIT = 10

        // Regex groups: the whole trigger phrase determines PERIOD
        private val TODAY_REGEX = Regex(
            """(?:what(?:'?s| is)(?: on)? (?:my )?(?:calendar|schedule)|what (?:have i|do i have)(?: got)? today|today(?:'?s)? (?:events?|schedule|calendar)|my schedule|what(?:'?s| is) today)""",
            RegexOption.IGNORE_CASE
        )
        private val TOMORROW_REGEX = Regex(
            """(?:what(?:'?s| is)(?: on)? (?:my )?(?:calendar|schedule) tomorrow|what (?:have i|do i have)(?: got)? tomorrow|tomorrow(?:'?s)? (?:events?|schedule|calendar)|my schedule tomorrow)""",
            RegexOption.IGNORE_CASE
        )
        private val WEEK_REGEX = Regex(
            """(?:this week|my week|what(?:'?s| is)(?: on)? (?:my )?(?:calendar|schedule) this week|what (?:have i|do i have)(?: got)? this week|week(?:'?s)? (?:events?|schedule|calendar))""",
            RegexOption.IGNORE_CASE
        )
        private val NEXT_REGEX = Regex(
            """(?:next (?:appointment|meeting|event|thing)|when(?:'?s| is) (?:my )?next|upcoming (?:appointment|meeting|event))""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Catch-all matcher for "is there anything on my calendar" / "calendar
         * events" / "agenda" phrasings the explicit period regexes miss.
         * Intentionally permissive — the fallback always assumes "today" since
         * that's the overwhelmingly common ask, and a wrong-day answer is
         * less bad than the LLM falling back to "Something went wrong."
         */
        private val CALENDAR_FALLBACK = Regex(
            """\b(?:calendar|schedule|agenda|appointments?|meetings?)\b""",
            RegexOption.IGNORE_CASE
        )

        private val CREATE_REGEX = Regex(
            """(?:add|create|schedule|book|put|set up)\s+(?:a\s+|an\s+|me\s+in\s+for\s+a?\s*)?(.+?)(?:\s+(?:on|for|at|this|next)\s+.+)?$""",
            RegexOption.IGNORE_CASE
        )
        private val CREATE_TRIGGERS = listOf(
            "add to (?:my )?calendar", "add .+ (?:to|on|in) (?:my )?calendar",
            "schedule (?:a |an )?(?:meeting|appointment|event|call|lunch|dinner|breakfast)",
            "create (?:a |an )?(?:meeting|appointment|event|reminder|call)",
            "book (?:a |an )?(?:meeting|appointment|event|call)",
            "put .+ in (?:my )?calendar"
        ).map { Regex(it, RegexOption.IGNORE_CASE) }
    }

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        // Check create triggers first
        if (CREATE_TRIGGERS.any { it.containsMatchIn(t) }) {
            return ToolInput(transcript, mapOf("action" to "create"))
        }
        // Order matters: tomorrow / week / next are more specific than the
        // bare-calendar TODAY_REGEX, which must check last.
        if (TOMORROW_REGEX.containsMatchIn(t))
            return ToolInput(transcript, mapOf("action" to "read", "period" to "tomorrow"))
        if (WEEK_REGEX.containsMatchIn(t))
            return ToolInput(transcript, mapOf("action" to "read", "period" to "week"))
        if (NEXT_REGEX.containsMatchIn(t))
            return ToolInput(transcript, mapOf("action" to "read", "period" to "next"))
        if (TODAY_REGEX.containsMatchIn(t))
            return ToolInput(transcript, mapOf("action" to "read", "period" to "today"))

        // Fall-through catch-all: any "calendar" / "schedule" / "agenda"
        // word combined with a question verb defaults to today's events.
        // This catches phrasings the explicit regexes miss after STT
        // normalisation strips apostrophes, e.g. "whats on my calendar
        // for today", "any calendar events", "do I have anything today".
        if (CALENDAR_FALLBACK.containsMatchIn(t)) {
            Log.d(TAG, "[CAL_MATCH_FALLBACK] \"$t\" → action=read period=today")
            return ToolInput(transcript, mapOf("action" to "read", "period" to "today"))
        }
        return null
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        if (input.param("action") == "create") {
            return createEvent(input.transcript)
        }
        val period = input.param("period").ifBlank { "today" }
        val (startMs, endMs) = periodBounds(period)
        val label = periodLabel(period)

        // Pre-flight permission check so we give the user a clear "I need
        // calendar access" message instead of a generic failure surfaced from
        // somewhere downstream.
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "[CAL_PERM_DENIED] READ_CALENDAR not granted")
            return ToolResult.Failure(
                "I need calendar access for that. Open Settings and grant the Jarvis " +
                    "app calendar permission, then try again."
            )
        }

        return try {
            val events = queryEvents(startMs, endMs, limitToOne = period == "next")
            if (events.isEmpty()) {
                ToolResult.Success(
                    spokenFeedback = "Nothing on your calendar $label.",
                    requiresLlmFollowUp = false
                )
            } else {
                val count = events.size
                val eventWord = if (count == 1) "event" else "events"
                val list = events.joinToString(", ")
                ToolResult.Success(
                    spokenFeedback = "You have $count $eventWord $label: $list.",
                    requiresLlmFollowUp = false
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CALENDAR permission denied at execute time")
            ToolResult.Failure("I don't have permission to read your calendar.")
        } catch (e: Exception) {
            Log.e(TAG, "Calendar query failed: ${e.message}", e)
            ToolResult.Failure("I couldn't read your calendar right now.")
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private data class PeriodBounds(val startMs: Long, val endMs: Long)

    private operator fun PeriodBounds.component1() = startMs
    private operator fun PeriodBounds.component2() = endMs

    private fun periodBounds(period: String): PeriodBounds {
        val cal = Calendar.getInstance()

        // Snap to start of today
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        return when (period) {
            "tomorrow" -> {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                val start = cal.timeInMillis
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                PeriodBounds(start, cal.timeInMillis)
            }
            "week" -> {
                val start = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 7)
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                PeriodBounds(start, cal.timeInMillis)
            }
            "next" -> {
                // Any event from now onwards; we'll LIMIT 1 in the query
                val start = System.currentTimeMillis()
                // Far future ceiling — 1 year
                cal.add(Calendar.YEAR, 1)
                PeriodBounds(start, cal.timeInMillis)
            }
            else -> { // "today"
                val start = cal.timeInMillis
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                PeriodBounds(start, cal.timeInMillis)
            }
        }
    }

    private fun periodLabel(period: String) = when (period) {
        "tomorrow" -> "tomorrow"
        "week"     -> "this week"
        "next"     -> "coming up"
        else       -> "today"
    }

    private fun createEvent(transcript: String): ToolResult {
        // Try ReminderParser for time extraction
        val parsed = ReminderParser.parse(transcript)
        val startMs = parsed?.triggerAtMs ?: run {
            // Fall back: look for day-of-week + optional time in transcript
            extractEventTime(transcript) ?: return ToolResult.Failure(
                "I couldn't work out when to add that. Try saying something like " +
                "\"add dentist appointment Friday at 3pm\"."
            )
        }
        val endMs = startMs + 60 * 60 * 1000L  // default 1-hour duration

        // Extract title: remove leading action words and time expressions
        val title = extractEventTitle(transcript).ifBlank {
            return ToolResult.Failure("I didn't catch a title for the event. What should I call it?")
        }

        // Find primary calendar
        val calId = primaryCalendarId() ?: return ToolResult.Failure(
            "No calendar found on this device. Please add a calendar account in Settings."
        )

        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val timeFmt = SimpleDateFormat("EEE d MMM 'at' HH:mm", Locale.getDefault())
            ToolResult.Success("Done. Added \"$title\" on ${timeFmt.format(startMs)}.")
        } catch (e: SecurityException) {
            ToolResult.Failure("I don't have permission to write to your calendar.")
        } catch (e: Exception) {
            Log.e(TAG, "Calendar insert failed: ${e.message}", e)
            ToolResult.Failure("I couldn't add that event.")
        }
    }

    private fun primaryCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection  = "${CalendarContract.Calendars.VISIBLE} = 1"
        return context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, selection, null,
            "${CalendarContract.Calendars._ID} ASC"
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }

    private fun extractEventTitle(transcript: String): String {
        val lower = transcript.lowercase()
        // Remove leading action phrases
        val stripped = lower
            .replace(Regex("""^(?:add|create|schedule|book|put|set up)\s+(?:a\s+|an\s+|me\s+in\s+for\s+a?\s*)?"""), "")
            .replace(Regex("""\s+(?:to|on|in|at|this|next|for)\s+(?:my\s+)?calendar.*$"""), "")
            .replace(Regex("""\s+(?:on|for|at|this|next)\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|today|tomorrow|\d{1,2}).*$"""), "")
            .replace(Regex("""\s+(?:at|in)\s+\d.*$"""), "")
            .trim()
        return stripped.replaceFirstChar { it.uppercaseChar() }
    }

    private val DAY_NAMES = mapOf(
        "monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY,
        "wednesday" to Calendar.WEDNESDAY, "thursday" to Calendar.THURSDAY,
        "friday" to Calendar.FRIDAY, "saturday" to Calendar.SATURDAY,
        "sunday" to Calendar.SUNDAY
    )

    private fun extractEventTime(transcript: String): Long? {
        val lower = transcript.lowercase()
        val cal   = Calendar.getInstance()

        // Advance to the named weekday (next occurrence)
        val matchedDay = DAY_NAMES.entries.firstOrNull { (name, _) -> lower.contains(name) }
        if (matchedDay != null) {
            val targetDow = matchedDay.value
            var diff = targetDow - cal.get(Calendar.DAY_OF_WEEK)
            if (diff <= 0) diff += 7
            cal.add(Calendar.DAY_OF_YEAR, diff)
        } else if (lower.contains("tomorrow")) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        // Set time from hh:mm am/pm or 24h
        val clockMatch = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)""", RegexOption.IGNORE_CASE).find(lower)
        if (clockMatch != null) {
            var hour = clockMatch.groupValues[1].toInt()
            val min  = clockMatch.groupValues[2].toIntOrNull() ?: 0
            val ampm = clockMatch.groupValues[3].lowercase()
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, min)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        // Only return if we matched a day — no time = assume 9am
        return if (matchedDay != null || lower.contains("tomorrow")) {
            cal.set(Calendar.HOUR_OF_DAY, 9)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis
        } else null
    }

    private fun queryEvents(startMs: Long, endMs: Long, limitToOne: Boolean): List<String> {
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.ALL_DAY
        )

        val selection = """
            ${CalendarContract.Events.DTSTART} >= ?
            AND ${CalendarContract.Events.DTSTART} <= ?
            AND ${CalendarContract.Events.DELETED} = 0
        """.trimIndent()

        val selectionArgs = arrayOf(startMs.toString(), endMs.toString())

        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${CalendarContract.Events.DTSTART} ASC"
        ) ?: return emptyList()

        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val results = mutableListOf<String>()

        cursor.use {
            while (it.moveToNext()) {
                if (limitToOne && results.isNotEmpty()) break
                if (results.size >= EVENT_LIMIT) break

                val title  = it.getString(0)?.takeIf { s -> s.isNotBlank() } ?: continue
                val dtStart = it.getLong(1)
                val allDay  = it.getInt(2) == 1

                val formatted = if (allDay) {
                    title
                } else {
                    "$title at ${timeFmt.format(dtStart)}"
                }
                results += formatted
            }
        }
        return results
    }
}
