package com.jarvis.assistant.tools.device

import android.Manifest
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

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
    override val description = "Read upcoming calendar events from the device calendar"
    override val requiresNetwork = false
    override val requiredPermissions = listOf(Manifest.permission.READ_CALENDAR)

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
    }

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        return when {
            TOMORROW_REGEX.containsMatchIn(t) ->
                ToolInput(transcript, mapOf("period" to "tomorrow"))
            WEEK_REGEX.containsMatchIn(t) ->
                ToolInput(transcript, mapOf("period" to "week"))
            NEXT_REGEX.containsMatchIn(t) ->
                ToolInput(transcript, mapOf("period" to "next"))
            TODAY_REGEX.containsMatchIn(t) ->
                ToolInput(transcript, mapOf("period" to "today"))
            else -> null
        }
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val period = input.param("period").ifBlank { "today" }
        val (startMs, endMs) = periodBounds(period)
        val label = periodLabel(period)

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
