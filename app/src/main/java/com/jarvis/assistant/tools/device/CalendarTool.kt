package com.jarvis.assistant.tools.device

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
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
 * Uses CalendarContract.Instances (not Events) so recurring events are
 * automatically expanded into real instances within the query window.
 * All-day events are handled correctly by reading the ALL_DAY flag and
 * formatting them without a time.
 *
 * Declined events (SELF_ATTENDEE_STATUS = ATTENDEE_STATUS_DECLINED) are
 * excluded unless the user's setting includes them.
 *
 * Diagnostic log markers:
 *   [CALENDAR_QUERY_START]           period=today|tomorrow|week|next
 *   [CALENDAR_PROVIDER_SELECTED]     source=Instances
 *   [CALENDAR_PERM_DENIED]
 *   [CALENDAR_RAW_EVENTS_COUNT]      count=N
 *   [CALENDAR_EVENT_INCLUDED]        title=... time=...
 *   [CALENDAR_EVENT_EXCLUDED]        reason=declined|blank_title
 *   [CALENDAR_FILTERED_EVENTS_COUNT] count=N
 *   [CALENDAR_QUERY_SUCCESS]
 *   [CALENDAR_QUERY_EMPTY]
 *   [CALENDAR_QUERY_FAILED]
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
    override val requiredPermissions = listOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

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
         * Catch-all for "any events today", "show my calendar", "agenda" etc.
         * Intentionally permissive — defaults to today.
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

        // SELF_ATTENDEE_STATUS value for declined — matches CalendarContract constant
        private const val ATTENDEE_STATUS_DECLINED = 2
    }

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        if (CREATE_TRIGGERS.any { it.containsMatchIn(t) }) {
            return ToolInput(transcript, mapOf("action" to "create"))
        }
        if (TOMORROW_REGEX.containsMatchIn(t))
            return ToolInput(transcript, mapOf("action" to "read", "period" to "tomorrow"))
        if (WEEK_REGEX.containsMatchIn(t))
            return ToolInput(transcript, mapOf("action" to "read", "period" to "week"))
        if (NEXT_REGEX.containsMatchIn(t))
            return ToolInput(transcript, mapOf("action" to "read", "period" to "next"))
        if (TODAY_REGEX.containsMatchIn(t))
            return ToolInput(transcript, mapOf("action" to "read", "period" to "today"))

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
        Log.d(TAG, "[CALENDAR_QUERY_START] period=$period")
        Log.d(TAG, "[CALENDAR_PROVIDER_SELECTED] source=Instances")

        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "[CALENDAR_PERM_DENIED] READ_CALENDAR not granted")
            return ToolResult.Failure(
                "I need calendar access for that. Open Settings and grant the Jarvis " +
                    "app calendar permission, then try again."
            )
        }

        val (startMs, endMs) = periodBounds(period)
        val label = periodLabel(period)
        val limitToOne = (period == "next")

        return try {
            val events = queryInstances(startMs, endMs, limitToOne)
            Log.d(TAG, "[CALENDAR_FILTERED_EVENTS_COUNT] count=${events.size}")

            if (events.isEmpty()) {
                Log.d(TAG, "[CALENDAR_QUERY_EMPTY] period=$period")
                ToolResult.Success(
                    spokenFeedback = buildEmptyResponse(period, label),
                    requiresLlmFollowUp = false
                )
            } else {
                Log.d(TAG, "[CALENDAR_QUERY_SUCCESS] period=$period count=${events.size}")
                ToolResult.Success(
                    spokenFeedback = buildSpokenSummary(events, period, label, limitToOne),
                    requiresLlmFollowUp = false
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "[CALENDAR_QUERY_FAILED] reason=permission_denied")
            ToolResult.Failure("I don't have permission to read your calendar.")
        } catch (e: Exception) {
            Log.e(TAG, "[CALENDAR_QUERY_FAILED] reason=${e.message}", e)
            ToolResult.Failure("I couldn't read your calendar.")
        }
    }

    // -------------------------------------------------------------------------
    // Spoken summary builders — match spec exactly
    // -------------------------------------------------------------------------

    private fun buildEmptyResponse(period: String, label: String): String = when (period) {
        "next" -> "You've got nothing coming up."
        else   -> "Nothing on your calendar $label."
    }

    private fun buildSpokenSummary(
        events: List<CalEvent>,
        period: String,
        label: String,
        limitToOne: Boolean,
    ): String {
        if (limitToOne || events.size == 1) {
            val e = events.first()
            return if (e.allDay) {
                "You've got one all-day event $label: ${e.title}."
            } else {
                "Your next event is ${e.title} at ${e.formattedTime}."
            }
        }
        val count = events.size
        val allAllDay = events.all { it.allDay }
        return when {
            allAllDay -> {
                val names = events.joinToString(", ") { it.title }
                "You've got $count all-day events $label: $names."
            }
            count == 2 -> {
                val a = events[0].spokenLabel
                val b = events[1].spokenLabel
                "You've got 2 things $label: $a and $b."
            }
            else -> {
                val all = events.dropLast(1).joinToString(", ") { it.spokenLabel }
                val last = events.last().spokenLabel
                "You've got $count things $label: $all, and $last."
            }
        }
    }

    // -------------------------------------------------------------------------
    // Instances query — handles recurring events correctly
    // -------------------------------------------------------------------------

    private data class CalEvent(
        val title: String,
        val startMs: Long,
        val allDay: Boolean,
    ) {
        private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val formattedTime: String get() = timeFmt.format(startMs)
        val spokenLabel: String get() = if (allDay) title else "$title at $formattedTime"
    }

    private fun queryInstances(startMs: Long, endMs: Long, limitToOne: Boolean): List<CalEvent> {
        // CalendarContract.Instances automatically expands recurring events
        // into concrete occurrences within the [startMs, endMs] window.
        val instancesUri: Uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .also { builder ->
                ContentUris.appendId(builder, startMs)
                ContentUris.appendId(builder, endMs)
            }
            .build()

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )

        // Exclude deleted events; include visible calendars only.
        // We leave SELF_ATTENDEE_STATUS filtering to the loop so we can log each exclusion.
        val selection = "${CalendarContract.Instances.DELETED} = 0"

        val cursor = context.contentResolver.query(
            instancesUri,
            projection,
            selection,
            null,
            "${CalendarContract.Instances.BEGIN} ASC"
        ) ?: run {
            Log.w(TAG, "[CALENDAR_QUERY_FAILED] reason=null_cursor")
            return emptyList()
        }

        val rawCount = cursor.count
        Log.d(TAG, "[CALENDAR_RAW_EVENTS_COUNT] count=$rawCount")

        val results = mutableListOf<CalEvent>()
        cursor.use {
            while (it.moveToNext()) {
                if (limitToOne && results.isNotEmpty()) break
                if (results.size >= EVENT_LIMIT) break

                val title  = it.getString(0)?.takeIf { s -> s.isNotBlank() } ?: run {
                    Log.d(TAG, "[CALENDAR_EVENT_EXCLUDED] reason=blank_title")
                    continue
                }
                val begin  = it.getLong(1)
                val allDay = it.getInt(2) == 1
                val attendeeStatus = if (it.isNull(3)) -1 else it.getInt(3)

                if (attendeeStatus == ATTENDEE_STATUS_DECLINED) {
                    Log.d(TAG, "[CALENDAR_EVENT_EXCLUDED] reason=declined title=\"$title\"")
                    continue
                }

                Log.d(TAG, "[CALENDAR_EVENT_INCLUDED] title=\"$title\" " +
                    "allDay=$allDay begin=$begin")
                results += CalEvent(title = title, startMs = begin, allDay = allDay)
            }
        }
        return results
    }

    // -------------------------------------------------------------------------
    // Period bounds
    // -------------------------------------------------------------------------

    private data class PeriodBounds(val startMs: Long, val endMs: Long)
    private operator fun PeriodBounds.component1() = startMs
    private operator fun PeriodBounds.component2() = endMs

    private fun periodBounds(period: String): PeriodBounds {
        val cal = Calendar.getInstance()
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
                val start = System.currentTimeMillis()
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

    // -------------------------------------------------------------------------
    // Create event
    // -------------------------------------------------------------------------

    private fun createEvent(transcript: String): ToolResult {
        val parsed = ReminderParser.parse(transcript)
        val startMs = parsed?.triggerAtMs ?: run {
            extractEventTime(transcript) ?: return ToolResult.Failure(
                "I couldn't work out when to add that. Try something like " +
                "\"add dentist Friday at 3pm\"."
            )
        }
        val endMs = startMs + 60 * 60 * 1000L

        val title = extractEventTitle(transcript).ifBlank {
            return ToolResult.Failure("I didn't catch a title for the event. What should I call it?")
        }

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
        val stripped = lower
            .replace(Regex("""^(?:add|create|schedule|book|put|set up)\s+(?:a\s+|an\s+|me\s+in\s+for\s+a?\s*)?"""), "")
            .replace(Regex("""\s+(?:to|on|in|at|this|next)\s+(?:my\s+)?calendar.*$"""), "")
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

        val matchedDay = DAY_NAMES.entries.firstOrNull { (name, _) -> lower.contains(name) }
        if (matchedDay != null) {
            val targetDow = matchedDay.value
            var diff = targetDow - cal.get(Calendar.DAY_OF_WEEK)
            if (diff <= 0) diff += 7
            cal.add(Calendar.DAY_OF_YEAR, diff)
        } else if (lower.contains("tomorrow")) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

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

        return if (matchedDay != null || lower.contains("tomorrow")) {
            cal.set(Calendar.HOUR_OF_DAY, 9)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis
        } else null
    }
}
