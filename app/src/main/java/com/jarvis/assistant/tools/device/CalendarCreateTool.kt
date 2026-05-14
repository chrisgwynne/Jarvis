package com.jarvis.assistant.tools.device

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.util.Log
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema
import java.util.Calendar
import java.util.TimeZone

/**
 * CalendarCreateTool — "schedule a meeting tomorrow at 3 pm", "add lunch
 * with Mike to my calendar at noon", "create a calendar event for Friday
 * 2 pm called dentist".
 *
 * Inserts directly via CalendarContract.Events on the primary calendar.
 * If no primary is found, falls back to ACTION_INSERT so the user picks.
 *
 * Pure local — no Google Calendar API.  Time parsing is intentionally
 * narrow: today/tomorrow + day name + "at HH (am|pm|:MM)".  Anything
 * fancier falls through to the LLM.
 */
class CalendarCreateTool(
    private val context: Context,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : Tool {

    override val name = "calendar_create"
    override val description = "Create a calendar event."
    override val requiresNetwork = false
    override val requiredPermissions = listOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    companion object {
        private const val TAG = "CalendarCreateTool"
        private val DAY_NAMES = mapOf(
            "monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY,
            "wednesday" to Calendar.WEDNESDAY, "thursday" to Calendar.THURSDAY,
            "friday" to Calendar.FRIDAY, "saturday" to Calendar.SATURDAY,
            "sunday" to Calendar.SUNDAY,
        )
        private val CREATE_RX = Regex(
            """\b(?:schedule|create|add|book|put|set\s+up)\s+(?:a\s+|an\s+|the\s+)?(?:calendar\s+event|event|meeting|appointment|reminder)?\s*(?:called|titled|for|named)?\s*['"]?(.+?)['"]?\s+(?:to\s+(?:my\s+)?calendar\s+)?(?:on\s+|for\s+|at\s+)(.+?)[\s.?!]*$""",
            RegexOption.IGNORE_CASE,
        )
    }

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        // Reject if it looks like a generic reminder/alarm/timer — those
        // have their own tools and we shouldn't double-fire.
        if (Regex("""\b(?:remind\s+me|set\s+(?:a\s+)?(?:alarm|timer))\b""",
                RegexOption.IGNORE_CASE).containsMatchIn(t)) return null
        val m = CREATE_RX.find(t) ?: return null
        val title = m.groupValues[1].trim()
        val whenSpec = m.groupValues[2].trim()
        if (title.isBlank() || whenSpec.isBlank()) return null
        val start = parseTimeSpec(whenSpec) ?: return null
        return ToolInput(transcript, mapOf(
            "title" to title,
            "startMs" to start.toString(),
        ))
    }

    override fun schema() = ToolSchema(
        name        = name,
        description = "Create a calendar event on the primary calendar.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "title"   to mapOf("type" to "string"),
                "startMs" to mapOf("type" to "string", "description" to "Start time epoch ms"),
            ),
            "required" to listOf("title","startMs"),
        ),
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        val title = input.param("title").ifBlank { return ToolResult.Failure("I didn't catch the event title.") }
        val start = input.param("startMs").toLongOrNull()
            ?: return ToolResult.Failure("I couldn't work out when to schedule that.")
        val end = start + 60 * 60 * 1000L      // default 1 hour

        return try {
            val calendarId = primaryCalendarId()
            if (calendarId == null) {
                // Fall through to the user-facing chooser.
                val intent = Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    putExtra(CalendarContract.Events.TITLE, title)
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return ToolResult.Success("Opened calendar to create $title.")
            }
            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val id = uri?.let { ContentUris.parseId(it) }
                ?: return ToolResult.Failure("That didn't work — couldn't create the event.")
            ToolResult.Success("Added $title to your calendar.", rawData = id.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Calendar event create failed", e)
            ToolResult.Failure("That didn't work — couldn't create the event.")
        }
    }

    private fun primaryCalendarId(): Long? {
        return try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.IS_PRIMARY,
                    CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
                null,
                "${CalendarContract.Calendars.IS_PRIMARY} DESC",
            )?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Calendar query failed", e); null
        }
    }

    internal fun parseTimeSpec(raw: String): Long? {
        val s = raw.lowercase().trim()
        val cal = Calendar.getInstance().apply { timeInMillis = clock() }
        // Day specifier
        when {
            "today" in s -> { /* leave date as today */ }
            "tomorrow" in s -> cal.add(Calendar.DAY_OF_YEAR, 1)
            else -> {
                val day = DAY_NAMES.entries.firstOrNull { it.key in s }?.value
                if (day != null) {
                    val today = cal.get(Calendar.DAY_OF_WEEK)
                    var delta = (day - today + 7) % 7
                    if (delta == 0) delta = 7
                    cal.add(Calendar.DAY_OF_YEAR, delta)
                }
            }
        }
        // Time-of-day
        val timeRx = Regex(
            """(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)?|(?:at\s+)?(noon|midnight)""",
            RegexOption.IGNORE_CASE,
        )
        val m = timeRx.find(s) ?: return null
        val (hour, minute) = if (m.groupValues[4].isNotBlank()) {
            if (m.groupValues[4].equals("noon", true)) 12 to 0 else 0 to 0
        } else {
            var h = m.groupValues[1].toIntOrNull() ?: return null
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val mer = m.groupValues[3].lowercase()
            if (mer == "pm" && h < 12) h += 12
            if (mer == "am" && h == 12) h = 0
            h to min
        }
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        // If we resolved to a time in the past today (and no day was given),
        // bump to tomorrow.
        if (cal.timeInMillis < clock() &&
            !s.contains("tomorrow") &&
            DAY_NAMES.keys.none { it in s }
        ) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }
}
