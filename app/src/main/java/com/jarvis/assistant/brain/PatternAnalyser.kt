package com.jarvis.assistant.brain

import android.util.Log
import com.jarvis.assistant.brain.db.dao.BrainEventDao
import com.jarvis.assistant.brain.db.dao.BrainPatternDao
import com.jarvis.assistant.brain.db.entity.BrainEvent
import com.jarvis.assistant.brain.db.entity.BrainPattern
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * PatternAnalyser — the DETECT layer.
 *
 * Runs periodic analysis over [BrainEventDao] to find:
 *   A. Time patterns    — event recurs in a consistent hour window
 *   B. Sequence patterns — event B reliably follows event A within 5 minutes
 *   C. Context patterns — event recurs under same (location + dayType + hour)
 *   D. Day patterns     — event is significantly more common on weekday or weekend
 *
 * Results are upserted into [BrainPatternDao].
 * Drift is detected by comparing recent-7-day rate against stored confidence.
 *
 * Minimum data requirements before any pattern is emitted:
 *   - At least 7 days of events in the DB
 *   - At least 5 occurrences for a pattern candidate
 *   - Consistency ≥ 50 % (medium confidence threshold)
 */
class PatternAnalyser(
    private val eventDao: BrainEventDao,
    private val patternDao: BrainPatternDao
) {
    companion object {
        private const val TAG = "PatternAnalyser"

        // Analysis window
        private const val WINDOW_DAYS       = 30
        private const val MIN_OCCURRENCES   = 5
        private const val MIN_DAYS_DATA     = 7

        // Minimum confidence to persist a pattern
        private const val MIN_CONFIDENCE    = 0.50f

        // Sequence detection: B must follow A within this window
        private const val SEQ_WINDOW_MS     = 5L * 60_000   // 5 minutes

        // Drift: if recent-7-day rate < historicalConf * DRIFT_THRESHOLD → decay
        private const val DRIFT_THRESHOLD   = 0.55f
        private const val DECAY_STEP        = 0.80f
        private const val DECAY_RECOVER     = 0.95f
    }

    /** Run the full analysis pass. Called every few hours by [BrainEngine]. */
    suspend fun analyse() {
        val since     = System.currentTimeMillis() - WINDOW_DAYS * 86_400_000L
        val allEvents = eventDao.getSince(since)
        val dayCount  = eventDao.countDistinctDaysSince(since)

        if (dayCount < MIN_DAYS_DATA) {
            Log.d(TAG, "Not enough data yet ($dayCount days, need $MIN_DAYS_DATA) — skipping")
            return
        }

        Log.d(TAG, "Analysing ${allEvents.size} events over $dayCount days")

        val byType = allEvents.groupBy { it.type }

        detectTimePatterns(byType, dayCount)
        detectSequencePatterns(allEvents, byType)
        detectContextPatterns(byType, dayCount)
        detectDayPatterns(byType, dayCount)
        applyDrift(byType, dayCount)

        patternDao.pruneWeak()
        Log.d(TAG, "Analysis complete")
    }

    // ── A. Time Patterns ──────────────────────────────────────────────────────

    /**
     * For each event type, check each hour-of-day bucket (0–23).
     * Confidence = (days this event occurred in this hour) / totalDays.
     */
    private suspend fun detectTimePatterns(
        byType: Map<String, List<BrainEvent>>,
        totalDays: Int
    ) {
        for ((typeName, events) in byType) {
            if (events.size < MIN_OCCURRENCES) continue

            // Group into hour buckets, then count distinct calendar days per bucket
            val byHour = events.groupBy { it.hourOfDay }
            for ((hour, hourEvents) in byHour) {
                if (hourEvents.size < MIN_OCCURRENCES) continue

                val distinctDays = hourEvents.map { calendarDay(it.timestamp) }.distinct().size
                val confidence   = distinctDays.toFloat() / totalDays
                if (confidence < MIN_CONFIDENCE) continue

                val windowStart = "%02d:00".format(hour)
                val windowEnd   = "%02d:59".format(hour)
                val key         = "TIME_${typeName}_$hour"
                val label       = confidenceLabel(confidence)
                val desc        = humanDescribeTime(typeName, hour, confidence)

                upsertPattern(BrainPattern(
                    patternKey       = key,
                    patternType      = "TIME",
                    eventType        = typeName,
                    timeWindowStart  = windowStart,
                    timeWindowEnd    = windowEnd,
                    dayContext       = "any",
                    occurrenceCount  = distinctDays,
                    totalChecks      = totalDays,
                    confidence       = confidence,
                    confidenceLabel  = label,
                    humanDescription = desc,
                    lastSeen         = hourEvents.maxOf { it.timestamp },
                    createdAt        = System.currentTimeMillis(),
                    updatedAt        = System.currentTimeMillis()
                ))
            }
        }
    }

    // ── B. Sequence Patterns ──────────────────────────────────────────────────

    /**
     * Check well-defined event pairs for A→B sequences.
     * Only checks pairs that are semantically meaningful (limited set to avoid noise).
     */
    private suspend fun detectSequencePatterns(
        allEvents: List<BrainEvent>,
        byType: Map<String, List<BrainEvent>>
    ) {
        val pairs = listOf(
            BrainEventType.BLUETOOTH_CONNECTED    to BrainEventType.MEDIA_PLAY_START,
            BrainEventType.BLUETOOTH_CONNECTED    to BrainEventType.APP_OPEN,
            BrainEventType.CHARGER_CONNECTED      to BrainEventType.SCREEN_OFF,
            BrainEventType.SCREEN_ON              to BrainEventType.USER_MESSAGE,
            BrainEventType.HEADPHONES_CONNECTED   to BrainEventType.MEDIA_PLAY_START,
            BrainEventType.USER_MESSAGE           to BrainEventType.MEDIA_PLAY_START,
            BrainEventType.LOCATION_HOME          to BrainEventType.CHARGER_CONNECTED,
            BrainEventType.ALARM_SET              to BrainEventType.SCREEN_OFF,
        )

        for ((triggerType, targetType) in pairs) {
            val triggers = byType[triggerType.name] ?: continue
            val targets  = byType[targetType.name]  ?: continue
            if (triggers.size < MIN_OCCURRENCES) continue

            var followCount = 0
            for (trigger in triggers) {
                val followed = targets.any { t ->
                    t.timestamp > trigger.timestamp &&
                    t.timestamp <= trigger.timestamp + SEQ_WINDOW_MS
                }
                if (followed) followCount++
            }

            val confidence = followCount.toFloat() / triggers.size
            if (confidence < MIN_CONFIDENCE) continue

            val key   = "SEQ_${triggerType.name}_TO_${targetType.name}"
            val label = confidenceLabel(confidence)
            val desc  = humanDescribeSequence(triggerType.name, targetType.name, confidence)

            upsertPattern(BrainPattern(
                patternKey        = key,
                patternType       = "SEQUENCE",
                eventType         = targetType.name,
                triggerEventType  = triggerType.name,
                dayContext        = "any",
                occurrenceCount   = followCount,
                totalChecks       = triggers.size,
                confidence        = confidence,
                confidenceLabel   = label,
                humanDescription  = desc,
                lastSeen          = targets.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis(),
                createdAt         = System.currentTimeMillis(),
                updatedAt         = System.currentTimeMillis()
            ))
        }
    }

    // ── C. Context Patterns ───────────────────────────────────────────────────

    /**
     * For each event type, group by (location, dayType, hour) signature.
     * Confidence = distinctDays(in this context) / totalDays(with this context).
     */
    private suspend fun detectContextPatterns(
        byType: Map<String, List<BrainEvent>>,
        totalDays: Int
    ) {
        for ((typeName, events) in byType) {
            if (events.size < MIN_OCCURRENCES) continue

            // Build context groups
            val byCtx = events.groupBy { e ->
                "${e.locationState}|${if (e.isWeekend) "weekend" else "weekday"}|${e.hourOfDay}"
            }

            for ((ctxKey, ctxEvents) in byCtx) {
                if (ctxEvents.size < MIN_OCCURRENCES) continue

                val parts       = ctxKey.split("|")
                val location    = parts[0]
                val dayType     = parts[1]
                val hour        = parts[2].toIntOrNull() ?: continue

                // Count total days that had this context (approximated as total days with any event)
                val denominator = (totalDays * 0.5f).toInt().coerceAtLeast(MIN_OCCURRENCES)
                val distinctDays = ctxEvents.map { calendarDay(it.timestamp) }.distinct().size
                val confidence   = distinctDays.toFloat() / denominator
                if (confidence < MIN_CONFIDENCE) continue

                val key  = "CTX_${typeName}_${location}_${dayType}_$hour"
                val desc = humanDescribeContext(typeName, location, dayType, hour, confidence)

                upsertPattern(BrainPattern(
                    patternKey       = key,
                    patternType      = "CONTEXT",
                    eventType        = typeName,
                    timeWindowStart  = "%02d:00".format(hour),
                    timeWindowEnd    = "%02d:59".format(hour),
                    locationContext  = location,
                    dayContext       = dayType,
                    occurrenceCount  = distinctDays,
                    totalChecks      = denominator,
                    confidence       = confidence,
                    confidenceLabel  = confidenceLabel(confidence),
                    humanDescription = desc,
                    lastSeen         = ctxEvents.maxOf { it.timestamp },
                    createdAt        = System.currentTimeMillis(),
                    updatedAt        = System.currentTimeMillis()
                ))
            }
        }
    }

    // ── D. Day Patterns ───────────────────────────────────────────────────────

    /**
     * If an event is ≥2× more frequent on weekdays than weekends (or vice versa),
     * emit a DAY pattern.
     */
    private suspend fun detectDayPatterns(
        byType: Map<String, List<BrainEvent>>,
        totalDays: Int
    ) {
        val weekdayDays = (totalDays * 5f / 7f).coerceAtLeast(1f)
        val weekendDays = (totalDays * 2f / 7f).coerceAtLeast(1f)

        for ((typeName, events) in byType) {
            if (events.size < MIN_OCCURRENCES) continue

            val weekdayCount = events.count { !it.isWeekend }.toFloat()
            val weekendCount = events.count {  it.isWeekend }.toFloat()
            val weekdayFreq  = weekdayCount / weekdayDays
            val weekendFreq  = weekendCount / weekendDays

            val dominantDay: String
            val ratio: Float
            val baseFreq: Float

            when {
                weekdayFreq > 0 && weekendFreq > 0 && weekdayFreq / weekendFreq >= 2.0f -> {
                    dominantDay = "weekday"; ratio = weekdayFreq / weekendFreq; baseFreq = weekdayFreq
                }
                weekendFreq > 0 && weekdayFreq > 0 && weekendFreq / weekdayFreq >= 2.0f -> {
                    dominantDay = "weekend"; ratio = weekendFreq / weekdayFreq; baseFreq = weekendFreq
                }
                else -> continue
            }

            val confidence = (baseFreq / (weekdayFreq + weekendFreq)).coerceIn(0f, 1f)
            if (confidence < MIN_CONFIDENCE) continue

            val key  = "DAY_${typeName}_$dominantDay"
            val desc = humanDescribeDay(typeName, dominantDay, ratio, confidence)

            upsertPattern(BrainPattern(
                patternKey       = key,
                patternType      = "DAY",
                eventType        = typeName,
                dayContext       = dominantDay,
                occurrenceCount  = if (dominantDay == "weekday") weekdayCount.toInt() else weekendCount.toInt(),
                totalChecks      = events.size,
                confidence       = confidence,
                confidenceLabel  = confidenceLabel(confidence),
                humanDescription = desc,
                lastSeen         = events.maxOf { it.timestamp },
                createdAt        = System.currentTimeMillis(),
                updatedAt        = System.currentTimeMillis()
            ))
        }
    }

    // ── Drift Detection ───────────────────────────────────────────────────────

    /**
     * Compare last-7-day frequency against stored confidence.
     * If significantly lower → apply decay.  If recovered → restore decay factor.
     */
    private suspend fun applyDrift(
        byType: Map<String, List<BrainEvent>>,
        totalDays: Int
    ) {
        val recentSince  = System.currentTimeMillis() - 7L * 86_400_000
        val recentDays   = 7f
        val allPatterns  = patternDao.getAll()

        for (pattern in allPatterns) {
            if (pattern.patternType == "SEQUENCE") continue  // sequence drift handled separately

            val recentEvents = byType[pattern.eventType]
                ?.filter { it.timestamp >= recentSince } ?: emptyList()

            val recentDailyRate = recentEvents.size.toFloat() / recentDays
            val historicalRate  = pattern.occurrenceCount.toFloat() / totalDays.coerceAtLeast(1)

            val newDecay = when {
                historicalRate > 0 && recentDailyRate / historicalRate < DRIFT_THRESHOLD -> {
                    Log.d(TAG, "Drift detected for ${pattern.patternKey}: " +
                        "recent=$recentDailyRate hist=$historicalRate → decaying")
                    (pattern.decayFactor * DECAY_STEP).coerceAtLeast(0.1f)
                }
                recentDailyRate >= historicalRate * DRIFT_THRESHOLD && pattern.decayFactor < 1f -> {
                    // Recovering
                    (pattern.decayFactor / DECAY_RECOVER).coerceAtMost(1f)
                }
                else -> pattern.decayFactor
            }

            if (newDecay != pattern.decayFactor) {
                patternDao.updateDecay(pattern.patternKey, newDecay)
            }
        }
    }

    // ── Upsert helper ─────────────────────────────────────────────────────────

    private suspend fun upsertPattern(new: BrainPattern) {
        val existing = patternDao.getByKey(new.patternKey)
        val toSave   = if (existing != null) {
            // Preserve id, lastSuggestedAt, acceptCount, decayFactor
            new.copy(
                id              = existing.id,
                createdAt       = existing.createdAt,
                decayFactor     = existing.decayFactor,
                lastSuggestedAt = existing.lastSuggestedAt,
                acceptCount     = existing.acceptCount
            )
        } else new

        patternDao.upsert(toSave)
        Log.v(TAG, "Upserted pattern: ${new.patternKey} conf=${new.confidence}")
    }

    // ── Human descriptions ────────────────────────────────────────────────────

    private fun humanDescribeTime(type: String, hour: Int, conf: Float): String {
        val timeStr  = "%02d:00".format(hour)
        val pct      = (conf * 100).toInt()
        val eventStr = eventTypeLabel(type)
        return "You $eventStr around $timeStr on $pct% of days."
    }

    private fun humanDescribeSequence(trigger: String, target: String, conf: Float): String {
        val pct = (conf * 100).toInt()
        return "After ${eventTypeLabel(trigger)}, you ${eventTypeLabel(target)} within 5 minutes $pct% of the time."
    }

    private fun humanDescribeContext(
        type: String, location: String, dayType: String, hour: Int, conf: Float
    ): String {
        val pct     = (conf * 100).toInt()
        val locStr  = if (location == "unknown") "" else " at $location"
        val dayStr  = if (dayType == "any") "" else " on $dayType${if (dayType.endsWith("y")) "s" else ""}"
        return "You ${eventTypeLabel(type)}$locStr$dayStr around %02d:00 — $pct%% of the time.".format(hour)
    }

    private fun humanDescribeDay(type: String, dominant: String, ratio: Float, conf: Float): String {
        val ratioStr = "%.1f×".format(ratio)
        return "You ${eventTypeLabel(type)} $ratioStr more on ${dominant}s than the other days."
    }

    private fun eventTypeLabel(typeName: String) = when (typeName) {
        BrainEventType.CHARGER_CONNECTED.name      -> "connect the charger"
        BrainEventType.CHARGER_DISCONNECTED.name   -> "unplug the charger"
        BrainEventType.SCREEN_ON.name              -> "turn the screen on"
        BrainEventType.SCREEN_OFF.name             -> "turn the screen off"
        BrainEventType.BLUETOOTH_CONNECTED.name    -> "connect a Bluetooth device"
        BrainEventType.BLUETOOTH_DISCONNECTED.name -> "disconnect Bluetooth"
        BrainEventType.HEADPHONES_CONNECTED.name   -> "plug in headphones"
        BrainEventType.MEDIA_PLAY_START.name       -> "start playing media"
        BrainEventType.MEDIA_PLAY_STOP.name        -> "stop media"
        BrainEventType.USER_MESSAGE.name           -> "talk to Jarvis"
        BrainEventType.ALARM_SET.name              -> "set an alarm"
        BrainEventType.TIMER_SET.name              -> "set a timer"
        BrainEventType.LOCATION_HOME.name          -> "arrive home"
        BrainEventType.LOCATION_AWAY.name          -> "leave home"
        else -> typeName.lowercase().replace('_', ' ')
    }

    private fun confidenceLabel(c: Float) = when {
        c >= 0.90f -> "very_high"
        c >= 0.75f -> "high"
        c >= 0.50f -> "medium"
        else       -> "low"
    }

    private fun calendarDay(timestamp: Long): LocalDate =
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
}
