package com.jarvis.assistant.proactive.scheduled

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CalendarReminderSource — reads upcoming events from the system
 * Calendar provider (Google Calendar / Samsung Calendar / etc.) and
 * converts them into [ScheduledReminderItem]s.
 *
 * Ignores all-day events: a 30-minute-before reminder for a date-only
 * event is nonsense and we already have [DAILY_AGENDA] for the morning
 * summary.
 *
 * Permission: [Manifest.permission.READ_CALENDAR].  Without it,
 * fetchUpcoming returns an empty list — never crash, never throw.
 */
class CalendarReminderSource(
    private val context: Context,
) : ScheduledReminderItemSource {

    override val source: ScheduledReminderSource = ScheduledReminderSource.CALENDAR

    override suspend fun fetchUpcoming(nowMs: Long, lookAheadMs: Long): List<ScheduledReminderItem> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "[CALENDAR_SOURCE_SKIP] reason=permission_missing")
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.EVENT_LOCATION,
            )
            val endMs = nowMs + lookAheadMs
            val selection =
                "${CalendarContract.Events.DTSTART} >= ? AND " +
                "${CalendarContract.Events.DTSTART} <= ? AND " +
                "${CalendarContract.Events.DELETED} = 0 AND " +
                "${CalendarContract.Events.ALL_DAY} = 0"
            val args = arrayOf(nowMs.toString(), endMs.toString())
            val results = mutableListOf<ScheduledReminderItem>()
            try {
                context.contentResolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    projection,
                    selection,
                    args,
                    "${CalendarContract.Events.DTSTART} ASC",
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id    = c.getLong(0)
                        val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                        val start = c.getLong(2)
                        val loc   = c.getString(4)?.takeIf { it.isNotBlank() }
                        results += ScheduledReminderItem(
                            source    = ScheduledReminderSource.CALENDAR,
                            sourceId  = id.toString(),
                            title     = title,
                            startMs   = start,
                            location  = loc,
                            fingerprint = "$start:$title:${loc ?: ""}",
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Calendar query failed", t)
            }
            results
        }
    }

    companion object { private const val TAG = "CalendarReminderSrc" }
}
