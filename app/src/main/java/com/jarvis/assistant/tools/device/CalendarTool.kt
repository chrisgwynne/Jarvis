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
 * CalendarTool — reads/creates events via the Android Calendar content provider.
 *
 * Read path (no network, no OAuth):
 *   1. Check READ_CALENDAR permission.
 *   2. Fetch visible calendar IDs from CalendarContract.Calendars.
 *   3. Query CalendarContract.Instances (expands recurring events automatically)
 *      filtered to those calendar IDs and the requested time window.
 *   4. Exclude declined events (SELF_ATTENDEE_STATUS == DECLINED).
 *   5. Summarise locally — no LLM involved.
 *   Fallback: if Instances returns 0, retry via CalendarContract.Events directly.
 *
 * Diagnostic log markers (filter: adb logcat -s CalendarTool):
 *   [CALENDAR_QUERY_START]           period=... tz=... start=... end=...
 *   [CALENDAR_PERM_DENIED]
 *   [CALENDAR_CALENDARS_FOUND]       count=N ids=...
 *   [CALENDAR_CALENDARS_NONE]        no visible calendars on device
 *   [CALENDAR_RAW_EVENTS_COUNT]      source=Instances|Events count=N
 *   [CALENDAR_INSTANCES_URI]         uri=...
 *   [CALENDAR_INSTANCES_FAILED]      reason=...
 *   [CALENDAR_INSTANCES_EMPTY]       falling back to Events
 *   [CALENDAR_EVENT_INCLUDED]        title=... allDay=... time=...
 *   [CALENDAR_EVENT_EXCLUDED]        reason=declined|blank_title title=...
 *   [CALENDAR_FILTERED_EVENTS_COUNT] count=N
 *   [CALENDAR_QUERY_SUCCESS]         period=... count=N
 *   [CALENDAR_QUERY_EMPTY]           period=...
 *   [CALENDAR_QUERY_FAILED]          reason=...
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
        description = "Read or create calendar events. action=read for queries, action=create to add events.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "action" to mapOf("type" to "string", "enum" to listOf("read", "create"), "description" to "read = query events, create = add a new event"),
                "period" to mapOf("type" to "string", "enum" to listOf("today", "tomorrow", "week", "next"), "description" to "Time period for read queries"),
                "title"  to mapOf("type" to "string", "description" to "Event title for create"),
                "when"   to mapOf("type" to "string", "description" to "Natural language time, e.g. Friday at 3pm"),
            ),
            "required" to listOf("action"),
        )
    )

    companion object {
        private const val TAG = "CalendarTool"
        private const val EVENT_LIMIT = 10
        private const val ATTENDEE_STATUS_DECLINED = 2

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
        private val CALENDAR_FALLBACK = Regex(
            """\b(?:calendar|schedule|agenda|appointments?|meetings?)\b""",
            RegexOption.IGNORE_CASE
        )
        private val CREATE_TRIGGERS = listOf(
            "add to (?:my )?calendar", "add .+ (?:to|on|in) (?:my )?calendar",
            "schedule (?:a |an )?(?:meeting|appointment|event|call|lunch|dinner|breakfast)",
            "create (?:a |an )?(?:meeting|appointment|event|reminder|call)",
            "book (?:a |an )?(?:meeting|appointment|event|call)",
            "put .+ in (?:my )?calendar",
        ).map { Regex(it, RegexOption.IGNORE_CASE) }
    }

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        if (CREATE_TRIGGERS.any { it.containsMatchIn(t) })
            return ToolInput(transcript, mapOf("action" to "create"))
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
        if (input.param("action") == "create") return createEvent(input.transcript)

        val period = input.param("period").ifBlank { "today" }

        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "[CALENDAR_PERM_DENIED] READ_CALENDAR not granted")
            return ToolResult.Failure("I need calendar permission first.")
        }

        val (startMs, endMs) = periodBounds(period)
        val tz = TimeZone.getDefault()
        Log.d(TAG, "[CALENDAR_QUERY_START] period=$period tz=${tz.id} start=$startMs end=$endMs")

        val calendarIds = visibleCalendarIds()
        if (calendarIds.isEmpty()) {
            Log.w(TAG, "[CALENDAR_CALENDARS_NONE] no visible calendars on device")
            return ToolResult.Failure(
                "No calendars found on your phone. Add a Google account in Settings and enable Calendar sync."
            )
        }
        Log.d(TAG, "[CALENDAR_CALENDARS_FOUND] count=${calendarIds.size} ids=${calendarIds.joinToString(",")}")

        val limitToOne = (period == "next")

        return try {
            val events = queryInstances(startMs, endMs, calendarIds, limitToOne)
            Log.d(TAG, "[CALENDAR_FILTERED_EVENTS_COUNT] count=${events.size}")

            if (events.isEmpty()) {
                Log.d(TAG, "[CALENDAR_QUERY_EMPTY] period=$period")
                ToolResult.Success(
                    spokenFeedback = buildEmptyResponse(period),
                    requiresLlmFollowUp = false,
                )
            } else {
                Log.d(TAG, "[CALENDAR_QUERY_SUCCESS] period=$period count=${events.size}")
                ToolResult.Success(
                    spokenFeedback = buildSpokenSummary(events, period, limitToOne),
                    requiresLlmFollowUp = false,
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

    // ─────────────────────────────────────────────────────────────────────────
    // Calendar list
    // ─────────────────────────────────────────────────────────────────────────

    private fun visibleCalendarIds(): List<Long> {
        val proj = arrayOf(CalendarContract.Calendars._ID)
        val sel  = "${CalendarContract.Calendars.VISIBLE} = 1"
        return try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, proj, sel, null, null,
            )?.use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0)) }
            } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "[CALENDAR_CALENDARS_FAILED] ${e.message}")
            emptyList()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Instances query (recurring-event aware)
    // ─────────────────────────────────────────────────────────────────────────

    private fun queryInstances(
        startMs: Long,
        endMs: Long,
        calendarIds: List<Long>,
        limitToOne: Boolean,
    ): List<CalEvent> {
        val instancesUri: Uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .also { b -> ContentUris.appendId(b, startMs); ContentUris.appendId(b, endMs) }
            .build()
        Log.d(TAG, "[CALENDAR_INSTANCES_URI] uri=$instancesUri")

        val proj = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.CALENDAR_ID,
        )

        // Filter to visible calendars only
        val placeholders = calendarIds.joinToString(",") { "?" }
        val selection    = "${CalendarContract.Instances.CALENDAR_ID} IN ($placeholders)"
        val selArgs      = calendarIds.map { it.toString() }.toTypedArray()

        val cursor = try {
            context.contentResolver.query(
                instancesUri, proj, selection, selArgs,
                "${CalendarContract.Instances.BEGIN} ASC",
            )
        } catch (e: Exception) {
            Log.w(TAG, "[CALENDAR_INSTANCES_FAILED] ${e.message}")
            null
        }

        val rawCount = cursor?.count ?: -1
        Log.d(TAG, "[CALENDAR_RAW_EVENTS_COUNT] source=Instances count=$rawCount")

        if (rawCount > 0 && cursor != null) {
            return collectFromCursor(cursor, limitToOne, colTitle = 0, colBegin = 1, colAllDay = 2, colStatus = 3)
        }
        cursor?.close()

        Log.w(TAG, "[CALENDAR_INSTANCES_EMPTY] falling back to Events direct query start=$startMs end=$endMs")
        return queryEventsDirect(startMs, endMs, calendarIds, limitToOne)
    }

    private fun queryEventsDirect(
        startMs: Long,
        endMs: Long,
        calendarIds: List<Long>,
        limitToOne: Boolean,
    ): List<CalEvent> {
        val proj = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.ALL_DAY,
        )
        val placeholders = calendarIds.joinToString(",") { "?" }
        val selection    = "(${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} < ?)" +
            " AND ${CalendarContract.Events.DELETED} = 0" +
            " AND ${CalendarContract.Events.CALENDAR_ID} IN ($placeholders)"
        val selArgs = arrayOf(startMs.toString(), endMs.toString()) + calendarIds.map { it.toString() }.toTypedArray()

        val cursor = try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI, proj, selection, selArgs,
                "${CalendarContract.Events.DTSTART} ASC",
            )
        } catch (e: Exception) {
            Log.w(TAG, "[CALENDAR_EVENTS_FAILED] ${e.message}")
            return emptyList()
        } ?: return emptyList()

        Log.d(TAG, "[CALENDAR_RAW_EVENTS_COUNT] source=Events count=${cursor.count}")
        return collectFromCursor(cursor, limitToOne, colTitle = 0, colBegin = 1, colAllDay = 2, colStatus = -1)
    }

    private fun collectFromCursor(
        cursor: android.database.Cursor,
        limitToOne: Boolean,
        colTitle: Int,
        colBegin: Int,
        colAllDay: Int,
        colStatus: Int,
    ): List<CalEvent> {
        val results = mutableListOf<CalEvent>()
        cursor.use { c ->
            while (c.moveToNext()) {
                if (limitToOne && results.isNotEmpty()) break
                if (results.size >= EVENT_LIMIT) break

                val title = c.getString(colTitle)?.takeIf { it.isNotBlank() } ?: run {
                    Log.d(TAG, "[CALENDAR_EVENT_EXCLUDED] reason=blank_title")
                    continue
                }
                val begin  = c.getLong(colBegin)
                val allDay = c.getInt(colAllDay) == 1

                if (colStatus >= 0 && !c.isNull(colStatus)) {
                    if (c.getInt(colStatus) == ATTENDEE_STATUS_DECLINED) {
                        Log.d(TAG, "[CALENDAR_EVENT_EXCLUDED] reason=declined title=\"$title\"")
                        continue
                    }
                }

                Log.d(TAG, "[CALENDAR_EVENT_INCLUDED] title=\"$title\" allDay=$allDay time=$begin")
                results += CalEvent(title = title, startMs = begin, allDay = allDay)
            }
        }
        return results
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Spoken response builders
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildEmptyResponse(period: String): String = when (period) {
        "tomorrow" -> "Nothing on your calendar tomorrow."
        "week"     -> "Nothing on your calendar this week."
        "next"     -> "Nothing coming up on your calendar."
        else       -> "Nothing on your calendar today."
    }

    private fun buildSpokenSummary(
        events: List<CalEvent>,
        period: String,
        limitToOne: Boolean,
    ): String {
        val label = periodLabel(period)

        if (limitToOne || events.size == 1) {
            val e = events.first()
            return if (e.allDay) "You've got ${e.title} — all day $label."
            else "You've got ${e.title} at ${e.spokenTime}."
        }

        return when (events.size) {
            2 -> "You've got 2 things $label: ${events[0].spokenLabel} and ${events[1].spokenLabel}."
            else -> {
                val head = events.dropLast(1).joinToString(", ") { it.spokenLabel }
                val tail = events.last().spokenLabel
                "You've got ${events.size} things $label: $head, and $tail."
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CalEvent model
    // ─────────────────────────────────────────────────────────────────────────

    private data class CalEvent(
        val title: String,
        val startMs: Long,
        val allDay: Boolean,
    ) {
        /** Colloquial time: "3pm", "3:30pm", "9am", "9:15am" */
        val spokenTime: String get() {
            val c = Calendar.getInstance().apply { timeInMillis = startMs }
            val h24  = c.get(Calendar.HOUR_OF_DAY)
            val min  = c.get(Calendar.MINUTE)
            val h12  = if (h24 % 12 == 0) 12 else h24 % 12
            val ampm = if (h24 < 12) "am" else "pm"
            return if (min == 0) "$h12$ampm" else "$h12:${min.toString().padStart(2, '0')}$ampm"
        }
        val spokenLabel: String get() = if (allDay) "$title (all day)" else "$title at $spokenTime"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Period bounds — uses device local timezone via Calendar.getInstance()
    // ─────────────────────────────────────────────────────────────────────────

    private data class PeriodBounds(val startMs: Long, val endMs: Long)

    private fun periodBounds(period: String): PeriodBounds {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis

        return when (period) {
            "tomorrow" -> {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                val start = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 1)
                PeriodBounds(start, cal.timeInMillis - 1)
            }
            "week" -> {
                cal.add(Calendar.DAY_OF_YEAR, 7)
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                PeriodBounds(todayStart, cal.timeInMillis)
            }
            "next" -> {
                val start = System.currentTimeMillis()
                cal.add(Calendar.YEAR, 1)
                PeriodBounds(start, cal.timeInMillis)
            }
            else -> { // today
                cal.add(Calendar.DAY_OF_YEAR, 1)
                PeriodBounds(todayStart, cal.timeInMillis - 1)
            }
        }
    }

    private fun periodLabel(period: String) = when (period) {
        "tomorrow" -> "tomorrow"
        "week"     -> "this week"
        "next"     -> "coming up"
        else       -> "today"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Create event
    // ─────────────────────────────────────────────────────────────────────────

    private fun createEvent(transcript: String): ToolResult {
        val parsed = ReminderParser.parse(transcript)
        val startMs = parsed?.triggerAtMs ?: (extractEventTime(transcript) ?: return ToolResult.Failure(
            "I couldn't work out when to add that. Try something like \"add dentist Friday at 3pm\"."
        ))
        val endMs = startMs + 60 * 60 * 1000L

        val title = extractEventTitle(transcript).ifBlank {
            return ToolResult.Failure("I didn't catch a title for the event. What should I call it?")
        }

        val calId = primaryCalendarId() ?: return ToolResult.Failure(
            "No calendar found on this device. Add a Google account in Settings and enable Calendar sync."
        )

        if (context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.Failure("I need calendar write permission to add events.")
        }

        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val fmt = SimpleDateFormat("EEE d MMM 'at' h:mma", Locale.getDefault())
            ToolResult.Success("Done. Added \"$title\" on ${fmt.format(startMs)}.")
        } catch (e: SecurityException) {
            ToolResult.Failure("I don't have permission to write to your calendar.")
        } catch (e: Exception) {
            Log.e(TAG, "Calendar insert failed: ${e.message}", e)
            ToolResult.Failure("I couldn't add that event.")
        }
    }

    private fun primaryCalendarId(): Long? {
        val proj = arrayOf(CalendarContract.Calendars._ID)
        val sel  = "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.IS_PRIMARY} = 1"
        val primary = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, proj, sel, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        if (primary != null) return primary

        // Fall back to any visible calendar
        val fallbackSel = "${CalendarContract.Calendars.VISIBLE} = 1"
        return context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, proj, fallbackSel, null,
            "${CalendarContract.Calendars._ID} ASC",
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }

    private fun extractEventTitle(transcript: String): String {
        val lower = transcript.lowercase()
        return lower
            .replace(Regex("""^(?:add|create|schedule|book|put|set up)\s+(?:a\s+|an\s+|me\s+in\s+for\s+a?\s*)?"""), "")
            .replace(Regex("""\s+(?:to|on|in|at|this|next)\s+(?:my\s+)?calendar.*$"""), "")
            .replace(Regex("""\s+(?:on|for|at|this|next)\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|today|tomorrow|\d{1,2}).*$"""), "")
            .replace(Regex("""\s+(?:at|in)\s+\d.*$"""), "")
            .trim()
            .replaceFirstChar { it.uppercaseChar() }
    }

    private val DAY_NAMES = mapOf(
        "monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY,
        "wednesday" to Calendar.WEDNESDAY, "thursday" to Calendar.THURSDAY,
        "friday" to Calendar.FRIDAY, "saturday" to Calendar.SATURDAY,
        "sunday" to Calendar.SUNDAY,
    )

    private fun extractEventTime(transcript: String): Long? {
        val lower = transcript.lowercase()
        val cal = Calendar.getInstance()

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
