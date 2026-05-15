package com.jarvis.assistant.ambient

/**
 * A learned behaviour pattern for a single [triggerType].
 *
 * Confidence and seen/dismissed counts are used by [RoutineLearningEngine]
 * to decide whether the pattern is strong enough to emit a proactive event.
 *
 * @param usualStartMinute  Minutes-from-midnight of the typical start of the
 *                          window this event fires in (0..1439).
 * @param usualEndMinute    End of the typical window (0..1439).
 * @param dayOfWeekMask     Bitmask of Calendar.DAY_OF_WEEK values (1=Sun..7=Sat).
 *                          0 means the pattern fires any day.
 * @param description       Human-readable description shown in diagnostics and
 *                          proactive text (e.g. "normally leave for football").
 * @param followUpAction    Optional follow-up action hint
 *                          (e.g. "want directions?").
 */
data class RoutinePattern(
    val id: Long = 0L,
    val triggerType: AmbientEventType,
    val usualStartMinute: Int,
    val usualEndMinute: Int,
    val dayOfWeekMask: Int = 0,
    val description: String,
    val followUpAction: String? = null,
    val confidence: Float,
    val lastSeenMs: Long,
    val seenCount: Int,
    val dismissedCount: Int,
) {
    /** True when the pattern is strong enough to emit a proactive nudge. */
    fun isConfident(): Boolean = confidence >= CONFIDENCE_THRESHOLD && seenCount >= MIN_OBSERVATIONS

    companion object {
        const val CONFIDENCE_THRESHOLD = 0.55f
        const val MIN_OBSERVATIONS = 3
    }
}
