package com.jarvis.assistant.proactive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Calendar
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * AppCalendarContextSource — [CalendarContextSource] backed by the Android
 * CalendarContract content provider.
 *
 * Caches the upcoming-meetings list for [cacheTtlMs] to avoid re-querying on
 * every proactive tick.  Returns empty lists silently when READ_CALENDAR is
 * not granted or the provider is unavailable — this source must never throw
 * into the proactive tick loop.
 */
class AppCalendarContextSource(
    private val context: Context,
    private val cacheTtlMs: Long = 60_000L,
    private val nowMsProvider: () -> Long = System::currentTimeMillis
) : CalendarContextSource {

    companion object {
        private const val TAG = "AppCalendarSource"

        /** Truncate meeting titles to a reasonable TTS length. */
        private const val TITLE_MAX_LEN = 60

        /**
         * Patterns that redact meeting-body junk that shouldn't be spoken.
         * Zoom/Teams/Meet URLs, phone numbers, conference-line prefixes.
         */
        private val URL_REGEX = Regex("""https?://\S+""")
        private val PHONE_REGEX = Regex("""\+?\d[\d\-\s().]{7,}\d""")
        private val PRIVATE_KEYWORDS = listOf("private", "personal", "confidential")

        /**
         * Strip URLs / phone numbers / obviously sensitive titles before they
         * reach TTS or a log line.  Returns null for titles that should be
         * suppressed entirely (private/personal/confidential keywords).
         */
        fun sanitiseTitle(raw: String?): String? {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return null
            val lower = s.lowercase()
            if (PRIVATE_KEYWORDS.any { lower.contains(it) }) return null
            val cleaned = s.replace(URL_REGEX, "").replace(PHONE_REGEX, "").trim()
            if (cleaned.isEmpty()) return null
            return cleaned.take(TITLE_MAX_LEN)
        }
    }

    private val mutex = Mutex()
    @Volatile private var cacheAtMs: Long = 0L
    @Volatile private var cachedUpcoming: List<CalendarMeeting> = emptyList()
    @Volatile private var cachedRemainingToday: Int = 0

    override suspend fun getUpcomingMeetings(lookAheadMs: Long): List<CalendarMeeting> {
        refreshIfStale()
        val now = nowMsProvider()
        return cachedUpcoming.filter { m ->
            !m.isAllDay && m.startMs in now..(now + lookAheadMs)
        }
    }

    override suspend fun getMeetingsRemainingToday(): Int {
        refreshIfStale()
        return cachedRemainingToday
    }

    private suspend fun refreshIfStale() = mutex.withLock {
        val now = nowMsProvider()
        if (now - cacheAtMs < cacheTtlMs) return@withLock
        val meetings = safeQueryToday(now)
        cachedUpcoming = meetings
        cachedRemainingToday = meetings.count { it.isAllDay || it.startMs >= now }
        cacheAtMs = now
    }

    private fun safeQueryToday(nowMs: Long): List<CalendarMeeting> {
        if (!hasReadPermission()) return emptyList()
        return try {
            queryToday(nowMs)
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CALENDAR denied at query time")
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Calendar query failed: ${e.message}")
            emptyList()
        }
    }

    private fun hasReadPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun queryToday(nowMs: Long): List<CalendarMeeting> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val dayStart = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59)
        val dayEnd = cal.timeInMillis

        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY
        )
        val selection = """
            ${CalendarContract.Events.DTSTART} >= ?
            AND ${CalendarContract.Events.DTSTART} <= ?
            AND ${CalendarContract.Events.DELETED} = 0
        """.trimIndent()
        val selectionArgs = arrayOf(dayStart.toString(), dayEnd.toString())

        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${CalendarContract.Events.DTSTART} ASC"
        ) ?: return emptyList()

        val out = mutableListOf<CalendarMeeting>()
        cursor.use {
            while (it.moveToNext()) {
                val rawTitle = it.getString(0)
                val dtStart = it.getLong(1)
                val dtEnd = if (it.isNull(2)) dtStart else it.getLong(2)
                val allDay = it.getInt(3) == 1
                out += CalendarMeeting(
                    startMs = dtStart,
                    endMs = dtEnd,
                    title = sanitiseTitle(rawTitle),
                    isAllDay = allDay
                )
            }
        }
        return out
    }

}
