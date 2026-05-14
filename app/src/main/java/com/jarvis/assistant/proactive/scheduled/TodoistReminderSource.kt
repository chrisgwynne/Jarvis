package com.jarvis.assistant.proactive.scheduled

import android.util.Log
import com.jarvis.assistant.todoist.TodoistClient
import com.jarvis.assistant.todoist.TodoistTask
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * TodoistReminderSource — feeds upcoming Todoist tasks (with a
 * datetime due) into the ScheduledReminderEngine.
 *
 * Tasks with only a [TodoistDue.date] (date-only) are ignored —
 * "today" with no time has no anchor for a 30/10-minute reminder.
 *
 * The provided [clientProvider] is queried per-fetch so a freshly-set
 * API token takes effect without an engine restart.  Null means
 * Todoist is unconfigured and the source becomes a no-op.
 */
class TodoistReminderSource(
    private val clientProvider: () -> TodoistClient?,
) : ScheduledReminderItemSource {

    override val source: ScheduledReminderSource = ScheduledReminderSource.TODOIST

    override suspend fun fetchUpcoming(nowMs: Long, lookAheadMs: Long): List<ScheduledReminderItem> {
        val client = clientProvider() ?: return emptyList<ScheduledReminderItem>()
            .also { Log.d(TAG, "[TODOIST_SOURCE_SKIP] reason=client_unconfigured") }
        val endMs = nowMs + lookAheadMs
        // Pull the "today + upcoming" window.  Todoist's filter language
        // covers it via "today | overdue | N days".  We then filter to
        // only those with concrete datetimes that fall in our window.
        val tasks: List<TodoistTask> = try {
            val today    = (client.getTodayTasks()   as? TodoistClient.Result.Ok)?.value ?: emptyList()
            val upcoming = (client.getUpcomingTasks(days = 2) as? TodoistClient.Result.Ok)?.value ?: emptyList()
            (today + upcoming).distinctBy { it.id }
        } catch (t: Throwable) {
            Log.w(TAG, "Todoist fetch failed", t); return emptyList()
        }
        val out = mutableListOf<ScheduledReminderItem>()
        for (task in tasks) {
            if (task.isCompleted) continue
            val datetime = task.due?.datetime ?: continue
            val startMs = parseRfc3339Millis(datetime) ?: continue
            if (startMs < nowMs - 60_000L) continue          // already past
            if (startMs > endMs) continue                    // beyond horizon
            out += ScheduledReminderItem(
                source     = ScheduledReminderSource.TODOIST,
                sourceId   = task.id,
                title      = task.content,
                startMs    = startMs,
                fingerprint = "$startMs:${task.content}:${task.priority}",
            )
        }
        return out
    }

    /**
     * Lenient RFC-3339 parser.  Handles "YYYY-MM-DDTHH:MM:SSZ" and the
     * "+HH:MM" / "-HH:MM" tz suffixes that Todoist emits.  Returns null
     * on any parse failure rather than throwing — the engine will just
     * skip the task.
     */
    private fun parseRfc3339Millis(s: String): Long? {
        // Strip trailing 'Z' or convert "+02:00" → "+0200" for SDF.
        val normalised = when {
            s.endsWith("Z") -> s.dropLast(1) + "+0000"
            s.length >= 6 && (s[s.length - 3] == ':') &&
                (s[s.length - 6] == '+' || s[s.length - 6] == '-') ->
                s.substring(0, s.length - 3) + s.substring(s.length - 2)
            else -> s
        }
        return listOf(
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
        ).firstNotNullOfOrNull { pattern ->
            try {
                val sdf = SimpleDateFormat(pattern, Locale.ROOT).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = false
                }
                sdf.parse(normalised)?.time
            } catch (_: Exception) { null }
        }.also { if (it == null) Log.d(TAG, "Skipped unparsable due='$s'") }
    }

    companion object { private const val TAG = "TodoistReminderSrc" }

    @Suppress("unused") private val keepDateImport: Date? = null
}
