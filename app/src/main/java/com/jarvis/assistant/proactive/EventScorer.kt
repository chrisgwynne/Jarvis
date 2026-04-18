package com.jarvis.assistant.proactive

import android.util.Log

/**
 * EventScorer — converts raw [ProactiveEvent] candidates into [ScoredEvent]
 * instances by applying the scoring formula and contextual penalties.
 *
 * ## Scoring formula
 *
 * 1. **Raw score** = `(urgency + relevance + confidence - annoyanceCost) / 3f`
 *    Dividing by 3 normalises the theoretical maximum (3.0 when all positive
 *    inputs = 1 and annoyanceCost = 0) to 1.0.
 *
 * 2. **Cooldown penalty** — applied when the event's dedupeKey was last
 *    surfaced within its cooldown window:
 *    `penalty = repetitionPenalty * (1 - msSinceLast / cooldownMs)`
 *    The penalty is proportional to how recently the key was surfaced: it is
 *    at its full weight right after surfacing and decreases linearly to zero
 *    at the edge of the cooldown window.
 *
 * 3. **Speaking penalty** — applied when Jarvis is currently producing TTS
 *    output.  Any interruption during active speech is jarring.
 *
 * 4. **Recent interaction penalty** — applied when the user interacted within
 *    [ProactiveConfig.recentInteractionWindowMs].  The user just spoke; they
 *    are already engaged and may not need a proactive nudge.
 *
 * 5. **Final score** = `clamp(rawScore - totalPenalty, 0f, 1f)`
 *
 * 6. The final score is mapped to an [InterruptLevel] using the thresholds in
 *    [ProactiveConfig].
 */
class EventScorer(
    private val config: ProactiveConfig,
    private val cooldownStore: CooldownStore
) {

    companion object {
        private const val TAG = "EventScorer"
    }

    // ── Public data class ─────────────────────────────────────────────────────

    /**
     * A [ProactiveEvent] decorated with scoring metadata produced by [score].
     *
     * @param event          The original event.
     * @param rawScore       Normalised base score before penalties [0, 1].
     * @param finalScore     Score after all penalties are applied; clamped to [0, 1].
     * @param interruptLevel The [InterruptLevel] mapped from [finalScore].
     * @param penalties      Named penalty contributions for debugging / logging.
     */
    data class ScoredEvent(
        val event: ProactiveEvent,
        val rawScore: Float,
        val finalScore: Float,
        val interruptLevel: InterruptLevel,
        val penalties: Map<String, Float>
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Score all [events] against the current [snapshot] and return a list of
     * [ScoredEvent] instances, sorted descending by [ScoredEvent.finalScore].
     */
    fun scoreAll(
        events: List<ProactiveEvent>,
        snapshot: ContextSnapshot
    ): List<ScoredEvent> = events
        .map { score(it, snapshot) }
        .sortedByDescending { it.finalScore }

    /**
     * Score a single [event] against [snapshot].
     */
    fun score(event: ProactiveEvent, snapshot: ContextSnapshot): ScoredEvent {
        // Step 1 — raw score
        val raw = (event.urgency + event.relevance + event.confidence - event.annoyanceCost) / 3f

        val penalties = mutableMapOf<String, Float>()

        // Step 2 — cooldown / repetition penalty
        // Cooldown stretches with each past ignore of this dedupeKey so that
        // suggestions the user doesn't engage with back off over time.
        val baseCooldownMs = cooldownMsForType(event.type)
        val ignoreCount    = cooldownStore.ignoreCount(event.dedupeKey)
        val cooldownMs     = (baseCooldownMs *
            (1f + ignoreCount * config.ignoreEscalationFactor)).toLong()
        val msSinceLast = cooldownStore.msSinceSurfaced(event.dedupeKey)
        val cooldownPenalty = if (msSinceLast < cooldownMs) {
            val fraction = 1f - (msSinceLast.toFloat() / cooldownMs.toFloat())
            config.repetitionPenalty * fraction.coerceIn(0f, 1f)
        } else {
            0f
        }
        if (cooldownPenalty > 0f) penalties["cooldown"] = cooldownPenalty

        // Step 3 — speaking penalty
        val speakingPenalty = if (snapshot.isJarvisSpeaking) config.speakingPenalty else 0f
        if (speakingPenalty > 0f) penalties["speaking"] = speakingPenalty

        // Step 4 — recent interaction penalty
        val recentInteractionPenalty = run {
            val lastMs = snapshot.lastUserInteractionTimeMillis ?: return@run 0f
            val age = snapshot.currentTimeMillis - lastMs
            if (age < config.recentInteractionWindowMs) config.recentInteractionPenalty else 0f
        }
        if (recentInteractionPenalty > 0f) penalties["recentInteraction"] = recentInteractionPenalty

        // Step 5 — apply total penalty
        val totalPenalty = cooldownPenalty + speakingPenalty + recentInteractionPenalty
        val finalScore   = (raw - totalPenalty).coerceIn(0f, 1f)

        // Step 6 — map to interrupt level
        val interruptLevel = when {
            finalScore >= config.activeThreshold  -> InterruptLevel.ACTIVE
            finalScore >= config.passiveThreshold -> InterruptLevel.PASSIVE
            else                                  -> InterruptLevel.NONE
        }

        Log.v(
            TAG,
            "score(${event.type} / ${event.dedupeKey}): " +
            "raw=${"%.3f".format(raw)} " +
            "penalties=${penalties.entries.joinToString { "${it.key}=${"%.3f".format(it.value)}" }} " +
            "final=${"%.3f".format(finalScore)} level=$interruptLevel"
        )

        return ScoredEvent(
            event          = event,
            rawScore       = raw.coerceIn(0f, 1f),
            finalScore     = finalScore,
            interruptLevel = interruptLevel,
            penalties      = penalties
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Returns the cooldown window in milliseconds for a given [ProactiveEventType].
     */
    private fun cooldownMsForType(type: ProactiveEventType): Long = when (type) {
        ProactiveEventType.LOW_BATTERY         -> config.cooldownLowBatteryMs
        ProactiveEventType.UPCOMING_REMINDER   -> config.cooldownUpcomingReminderMs
        ProactiveEventType.MISSED_CALL         -> config.cooldownMissedCallMs
        ProactiveEventType.BEHAVIORAL_LEARNING -> config.minGlobalGapMs
    }
}
