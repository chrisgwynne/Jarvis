package com.jarvis.assistant.ambient

import android.util.Log
import com.jarvis.assistant.ambient.db.RoutinePatternDao
import com.jarvis.assistant.ambient.db.RoutinePatternEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * RoutineLearningEngine — periodically analyses [AmbientEventStore] history
 * and upserts [RoutinePattern]s in the DB.
 *
 * Algorithm: simple time-window frequency analysis.  For each tracked
 * [AmbientEventType], events are grouped by (type, hour-of-day, day-of-week)
 * bucket.  When a bucket has fired enough times its confidence rises; if it
 * stops firing, confidence decays (halved every 14 days without a sighting).
 *
 * No ML — just counts.  This is intentional: transparent, testable, private.
 */
class RoutineLearningEngine(
    private val store: AmbientEventStore,
    private val dao: RoutinePatternDao,
    private val scope: CoroutineScope,
    private val settingsProvider: () -> AmbientSettings = { AmbientSettings() },
    /** How far back to look for pattern data, in milliseconds. */
    private val lookbackMs: Long = 30L * 24 * 60 * 60 * 1000L,
    /** How often to re-run the analysis loop. */
    private val intervalMs: Long = 6 * 60 * 60 * 1000L,
) {
    companion object {
        private const val TAG = "RoutineLearningEngine"
        private val TRACKED_TYPES = setOf(
            AmbientEventType.ARRIVED_HOME,
            AmbientEventType.LEFT_HOME,
            AmbientEventType.APP_OPENED,
            AmbientEventType.CONNECTED_CAR_BLUETOOTH,
            AmbientEventType.CALENDAR_EVENT_SOON,
        )
    }

    fun start() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (settingsProvider().learningEnabled) {
                    try { analyse() } catch (e: Exception) {
                        Log.w(TAG, "Analysis run failed", e)
                    }
                }
                delay(intervalMs)
            }
        }
    }

    /** Expose current confident patterns without waiting for a full analysis run. */
    suspend fun confidentPatterns(): List<RoutinePattern> =
        dao.getConfident(RoutinePattern.CONFIDENCE_THRESHOLD, RoutinePattern.MIN_OBSERVATIONS)
            .map { it.toDomain() }

    suspend fun recordDismissal(patternId: Long) { dao.recordDismissal(patternId) }
    suspend fun recordAccept(patternId: Long)    { dao.recordAccept(patternId) }
    suspend fun resetAll()                        { dao.deleteAll() }
    suspend fun allPatterns(): List<RoutinePattern> = dao.getAll().map { it.toDomain() }

    private suspend fun analyse() {
        val fromMs = System.currentTimeMillis() - lookbackMs
        val events = store.loadSince(fromMs)
        if (events.isEmpty()) return

        for (type in TRACKED_TYPES) {
            val typeEvents = events.filter { it.type == type }
            if (typeEvents.size < RoutinePattern.MIN_OBSERVATIONS) continue
            analyseType(type, typeEvents)
        }
        Log.d(TAG, "[ROUTINE_LEARNING] analysed ${events.size} events for ${TRACKED_TYPES.size} types")
    }

    private suspend fun analyseType(type: AmbientEventType, events: List<AmbientEvent>) {
        // Group into (hour, dayMask) buckets
        data class Bucket(val startMinute: Int, val endMinute: Int, val dayMask: Int)

        val buckets = mutableMapOf<Bucket, Int>()
        for (e in events) {
            val cal = Calendar.getInstance().apply { timeInMillis = e.timestampMs }
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val min  = cal.get(Calendar.MINUTE)
            val startMinute = (hour * 60 + min).let { it - (it % 30) } // round to 30-min slot
            val endMinute = startMinute + 30
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            val key = Bucket(startMinute, endMinute, 1 shl dow)
            buckets[key] = (buckets[key] ?: 0) + 1
        }

        val best = buckets.maxByOrNull { it.value } ?: return
        val count = best.value
        val confidence = (count.toFloat() / events.size.toFloat()).coerceIn(0f, 1f)
        if (count < RoutinePattern.MIN_OBSERVATIONS) return

        val existing = dao.getBestForType(type.name)
        val description = buildDescription(type, best.key.startMinute, best.key.dayMask)

        if (existing != null) {
            dao.update(existing.copy(
                usualStartMinute = best.key.startMinute,
                usualEndMinute   = best.key.endMinute,
                dayOfWeekMask    = best.key.dayMask,
                description      = description,
                confidence       = ((existing.confidence + confidence) / 2f).coerceIn(0f, 1f),
                lastSeenMs       = System.currentTimeMillis(),
                seenCount        = existing.seenCount + count,
            ))
        } else {
            dao.insert(RoutinePatternEntity(
                triggerType      = type.name,
                usualStartMinute = best.key.startMinute,
                usualEndMinute   = best.key.endMinute,
                dayOfWeekMask    = best.key.dayMask,
                description      = description,
                followUpAction   = null,
                confidence       = confidence,
                lastSeenMs       = System.currentTimeMillis(),
                seenCount        = count,
                dismissedCount   = 0,
            ))
        }
    }

    private fun buildDescription(type: AmbientEventType, startMinute: Int, dayMask: Int): String {
        val timeLabel = minuteToLabel(startMinute)
        val dayLabel  = dayMaskToLabel(dayMask)
        return when (type) {
            AmbientEventType.LEFT_HOME             -> "normally leaves home around $timeLabel$dayLabel"
            AmbientEventType.ARRIVED_HOME          -> "normally arrives home around $timeLabel$dayLabel"
            AmbientEventType.APP_OPENED            -> "normally opens this app around $timeLabel$dayLabel"
            AmbientEventType.CONNECTED_CAR_BLUETOOTH -> "normally connects car around $timeLabel$dayLabel"
            AmbientEventType.CALENDAR_EVENT_SOON   -> "normally leaves for events around $timeLabel$dayLabel"
            else -> "routine pattern around $timeLabel$dayLabel"
        }
    }

    private fun minuteToLabel(minute: Int): String {
        val h = (minute / 60) % 24
        val m = minute % 60
        val ampm = if (h < 12) "am" else "pm"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return if (m == 0) "$h12$ampm" else "$h12:${m.toString().padStart(2, '0')}$ampm"
    }

    private fun dayMaskToLabel(mask: Int): String {
        val days = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val names = days.filterIndexed { i, _ -> mask and (1 shl (i + 1)) != 0 }
        if (names.isEmpty() || names.size == 7) return ""
        val weekday = names.size >= 5 && names.containsAll(listOf("Mon","Tue","Wed","Thu","Fri"))
        return " on " + when {
            weekday && names.size == 5 -> "weekdays"
            names.size == 2 && names.containsAll(listOf("Sat","Sun")) -> "weekends"
            else -> names.joinToString("/")
        }
    }
}
