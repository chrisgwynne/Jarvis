package com.jarvis.assistant.brain

import com.jarvis.assistant.brain.db.dao.BrainPatternDao
import com.jarvis.assistant.brain.db.entity.BrainPattern
import com.jarvis.assistant.memory.ProfileMemoryService
import com.jarvis.assistant.memory.db.entity.FactCategory

/**
 * PersonalModel — the MODEL layer.
 *
 * Converts high-confidence [BrainPattern] records into readable traits that
 * describe the user's behavioural profile.  Traits are:
 *
 *   1. Returned as a compact [Traits] object for the prediction engine.
 *   2. Persisted to [ProfileMemoryService] under "brain.*" fact keys so the
 *      LLM prompt automatically includes them without any extra wiring.
 *
 * Only patterns with [BrainPattern.effectiveConfidence] ≥ 0.65 are promoted
 * to traits, keeping the profile clean and accurate.
 */
class PersonalModel(
    private val patternDao: BrainPatternDao,
    private val profileMemory: ProfileMemoryService
) {
    companion object {
        private const val MIN_TRAIT_CONFIDENCE = 0.65f
    }

    data class Traits(
        /** Estimated bedtime hour, e.g. 23 for "around 11 pm". */
        val typicalBedtimeHour: Int? = null,

        /** Charging window description, e.g. "22:00–22:59". */
        val chargeWindow: String? = null,

        /** 0–1 probability that media plays in the morning (06–10). */
        val morningMusicProbability: Float? = null,

        /** Whether user tends to charge more on weekdays or weekends (or null = no clear pattern). */
        val chargeDayBias: String? = null,

        /** Whether user talks to Jarvis more on a particular day type. */
        val conversationDayBias: String? = null,

        /** Natural-language summary of all traits — injected into LLM context. */
        val summary: String
    )

    /**
     * Build the current [Traits] from stored patterns and persist them to
     * [ProfileMemoryService].  Should be called after every analysis run.
     */
    suspend fun refresh(): Traits {
        val patterns = patternDao.getAboveConfidence(MIN_TRAIT_CONFIDENCE)

        val bedtimeHour       = deriveBedtime(patterns)
        val chargeWindow      = deriveChargeWindow(patterns)
        val morningMusicProb  = deriveMorningMusicProb(patterns)
        val chargeDayBias     = deriveDayBias(BrainEventType.CHARGER_CONNECTED.name, patterns)
        val convDayBias       = deriveDayBias(BrainEventType.USER_MESSAGE.name, patterns)

        val summary = buildSummary(bedtimeHour, chargeWindow, morningMusicProb, chargeDayBias)

        // Persist traits to profile so LLM can use them naturally
        if (bedtimeHour != null) {
            profileMemory.setFact(
                "brain.bedtime",
                "usually around %02d:00".format(bedtimeHour),
                FactCategory.ROUTINE
            )
        }
        if (chargeWindow != null) {
            profileMemory.setFact("brain.charge_window", chargeWindow, FactCategory.ROUTINE)
        }
        if (morningMusicProb != null && morningMusicProb >= 0.65f) {
            profileMemory.setFact(
                "brain.morning_media",
                "often plays music in the morning (${(morningMusicProb * 100).toInt()}% probability)",
                FactCategory.ROUTINE
            )
        }

        return Traits(
            typicalBedtimeHour     = bedtimeHour,
            chargeWindow           = chargeWindow,
            morningMusicProbability = morningMusicProb,
            chargeDayBias          = chargeDayBias,
            conversationDayBias    = convDayBias,
            summary                = summary
        )
    }

    // ── Derivations ───────────────────────────────────────────────────────────

    private fun deriveBedtime(patterns: List<BrainPattern>): Int? {
        // Bedtime = hour of SCREEN_OFF with highest confidence in the 21:00–02:00 range
        return patterns
            .filter {
                it.patternType == "TIME" &&
                it.eventType   == BrainEventType.SCREEN_OFF.name
            }
            .mapNotNull { p ->
                val hour = p.timeWindowStart?.take(2)?.toIntOrNull() ?: return@mapNotNull null
                if (hour in 21..23 || hour in 0..2) Pair(hour, p.effectiveConfidence)
                else null
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun deriveChargeWindow(patterns: List<BrainPattern>): String? {
        return patterns
            .filter {
                it.patternType == "TIME" &&
                it.eventType   == BrainEventType.CHARGER_CONNECTED.name
            }
            .maxByOrNull { it.effectiveConfidence }
            ?.let { p -> "${p.timeWindowStart}–${p.timeWindowEnd}" }
    }

    private fun deriveMorningMusicProb(patterns: List<BrainPattern>): Float? {
        // Morning = hours 6–10
        val morningPattern = patterns
            .filter {
                it.patternType == "TIME" &&
                it.eventType   == BrainEventType.MEDIA_PLAY_START.name
            }
            .filter {
                val hour = it.timeWindowStart?.take(2)?.toIntOrNull() ?: return@filter false
                hour in 6..10
            }
            .maxByOrNull { it.effectiveConfidence }
        return morningPattern?.effectiveConfidence
    }

    private fun deriveDayBias(eventTypeName: String, patterns: List<BrainPattern>): String? {
        val dayPattern = patterns
            .filter { it.patternType == "DAY" && it.eventType == eventTypeName }
            .maxByOrNull { it.effectiveConfidence }
        return dayPattern?.dayContext  // "weekday" or "weekend"
    }

    private fun buildSummary(
        bedtimeHour: Int?,
        chargeWindow: String?,
        morningMusicProb: Float?,
        chargeDayBias: String?
    ): String {
        if (bedtimeHour == null && chargeWindow == null && morningMusicProb == null) {
            return ""
        }
        return buildString {
            append("Behavioural profile: ")
            val parts = mutableListOf<String>()
            if (bedtimeHour != null)
                parts += "usually goes to sleep around %02d:00".format(bedtimeHour)
            if (chargeWindow != null)
                parts += "typically charges between $chargeWindow"
            if (morningMusicProb != null && morningMusicProb >= 0.65f)
                parts += "often plays music in the morning"
            if (chargeDayBias != null)
                parts += "charges more on ${chargeDayBias}s"
            append(parts.joinToString("; "))
            append(".")
        }
    }
}
