package com.jarvis.assistant.brain

import com.jarvis.assistant.brain.db.dao.BrainEventDao
import com.jarvis.assistant.brain.db.dao.BrainPatternDao
import com.jarvis.assistant.brain.db.entity.BrainPattern
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * PredictionEngine — the PREDICT layer.
 *
 * Given the current device context, scores all known patterns and returns
 * an ordered list of predictions: what is the user likely to do next?
 *
 * Scoring formula:
 *   finalScore = pattern.effectiveConfidence
 *                * timeRelevance      (how close is current hour to pattern window)
 *                * recencyBonus       (pattern seen recently → more reliable)
 *                * recentTriggerBonus (for SEQUENCE: trigger event just happened)
 */
class PredictionEngine(
    private val patternDao: BrainPatternDao,
    private val eventDao: BrainEventDao
) {
    companion object {
        private const val MIN_SCORE      = 0.45f
        private const val RECENT_MS      = 20L * 60_000     // 20 minutes for trigger check
        private const val SUGGEST_GAP_MS = 90L * 60_000     // min 90 min between same suggestion
    }

    data class Prediction(
        val eventType: String,
        val score: Float,
        val pattern: BrainPattern,
        /** Natural-language explanation for the suggestion text. */
        val reasoning: String
    )

    /**
     * Build a ranked list of predictions for the current moment.
     * Returns empty list if there is not enough data or nothing qualifies.
     */
    suspend fun predict(): List<Prediction> {
        val now     = LocalDateTime.now()
        val hour    = now.hour
        val dow     = now.dayOfWeek
        val dayCtx  = if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) "weekend" else "weekday"
        val nowMs   = System.currentTimeMillis()

        val allPatterns = patternDao.getAll()
        val results     = mutableListOf<Prediction>()

        // Recent events (last 20 min) — used for SEQUENCE trigger matching
        val recentEvents = eventDao.getSince(nowMs - RECENT_MS)

        for (pattern in allPatterns) {
            if (pattern.effectiveConfidence < MIN_SCORE) continue

            // Skip if we suggested this too recently
            if (nowMs - pattern.lastSuggestedAt < SUGGEST_GAP_MS) continue

            val score = scorePattern(pattern, hour, dayCtx, recentEvents, nowMs)
            if (score < MIN_SCORE) continue

            results += Prediction(
                eventType = pattern.eventType,
                score     = score,
                pattern   = pattern,
                reasoning = pattern.humanDescription
            )
        }

        return results.sortedByDescending { it.score }
    }

    private suspend fun scorePattern(
        p: BrainPattern,
        currentHour: Int,
        dayCtx: String,
        recentEvents: List<com.jarvis.assistant.brain.db.entity.BrainEvent>,
        nowMs: Long
    ): Float {
        var score = p.effectiveConfidence

        // Time relevance: full score if within the pattern's window, half score if 1 hour away
        val patternHour = p.timeWindowStart?.take(2)?.toIntOrNull()
        if (patternHour != null) {
            val hourDiff = minOf(
                Math.abs(currentHour - patternHour),
                24 - Math.abs(currentHour - patternHour)  // handle midnight wrap
            )
            score *= when (hourDiff) {
                0    -> 1.00f
                1    -> 0.70f
                2    -> 0.40f
                else -> 0.10f
            }
        }

        // Day context match
        val dayMatch = when {
            p.dayContext == null || p.dayContext == "any" -> true
            p.dayContext == dayCtx -> true
            else -> false
        }
        if (!dayMatch) return 0f

        // For SEQUENCE patterns: heavy bonus if the trigger event happened recently
        if (p.patternType == "SEQUENCE" && p.triggerEventType != null) {
            val triggerRecent = recentEvents.any { it.type == p.triggerEventType }
            if (!triggerRecent) {
                // Sequence patterns are only useful when the trigger just fired
                return 0f
            }
            score *= 1.25f  // boost for live trigger
        }

        // Recency bonus: patterns seen in the last 7 days are more reliable
        val daysSinceLastSeen = (nowMs - p.lastSeen) / 86_400_000f
        score *= when {
            daysSinceLastSeen <= 7f  -> 1.0f
            daysSinceLastSeen <= 14f -> 0.85f
            daysSinceLastSeen <= 30f -> 0.70f
            else                     -> 0.50f
        }

        return score.coerceIn(0f, 1f)
    }
}
