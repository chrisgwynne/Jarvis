package com.jarvis.assistant.core.goals

import com.jarvis.assistant.core.situations.Situation
import com.jarvis.assistant.core.situations.SituationType

/**
 * GoalEngine — turns currently-active [Situation]s into upsert-able [Goal]s.
 *
 * This is pure domain logic (no Room, no clock other than what the caller
 * passes) so it can be unit-tested with scripted situation lists. The
 * actual persistence happens when the caller forwards the [Intent] list to
 * [GoalStore.upsert].
 *
 * The engine never *completes* a goal. Completion is driven by plan /
 * outcome signals — see [com.jarvis.assistant.core.outcomes.OutcomeRecorder
 * .recordPlanOutcome] which closes goals when the originating plan finishes.
 *
 * The mapping is intentionally small and declarative. One situation can
 * only map to one goal type; ambiguous cases collapse to [GoalType.AD_HOC].
 */
class GoalEngine {

    /** A pending upsert. The caller applies these to [GoalStore]. */
    data class Intent(
        val type: GoalType,
        val title: String,
        val rootDedupeKey: String,
        val ttlMs: Long,
        val originSituation: SituationType,
        val evidence: List<String>,
    )

    fun derive(situations: List<Situation>): List<Intent> {
        val out = mutableListOf<Intent>()
        for (s in situations) {
            val intent = when (s.type) {
                SituationType.LOW_BATTERY_BEFORE_TRAVEL,
                SituationType.LEAVING_HOME_SOON,
                -> Intent(
                    type = GoalType.GET_READY_TO_LEAVE,
                    title = "Getting ready to leave",
                    rootDedupeKey = "goal:leave:${dayKey(s.createdAtMs)}",
                    ttlMs = 2 * HOUR_MS,
                    originSituation = s.type,
                    evidence = s.evidence,
                )
                SituationType.LIKELY_COMMUTE_START -> Intent(
                    type = GoalType.HANDLE_COMMUTE,
                    title = "Morning commute",
                    rootDedupeKey = "goal:commute:${dayKey(s.createdAtMs)}",
                    ttlMs = 2 * HOUR_MS,
                    originSituation = s.type,
                    evidence = s.evidence,
                )
                SituationType.IN_MEETING_AND_UNAVAILABLE -> Intent(
                    type = GoalType.PREPARE_FOR_MEETING,
                    title = "In a meeting",
                    rootDedupeKey = "goal:meeting:${dayKey(s.createdAtMs)}",
                    ttlMs = 3 * HOUR_MS,
                    originSituation = s.type,
                    evidence = s.evidence,
                )
                SituationType.MISSED_CALL_WHILE_NOW_FREE -> Intent(
                    type = GoalType.RETURN_MISSED_CALL,
                    title = "Return missed call",
                    rootDedupeKey = "goal:missed_call",
                    ttlMs = 12 * HOUR_MS,
                    originSituation = s.type,
                    evidence = s.evidence,
                )
                SituationType.ARRIVING_HOME_WITH_PENDING_TASKS,
                SituationType.HEADPHONES_IN_URGENT_NOTIFICATION_CONTEXT,
                -> Intent(
                    type = GoalType.CLEAR_NOTIFICATIONS,
                    title = "Clear pending tasks",
                    rootDedupeKey = "goal:clear_tasks:${dayKey(s.createdAtMs)}",
                    ttlMs = 6 * HOUR_MS,
                    originSituation = s.type,
                    evidence = s.evidence,
                )
                SituationType.TIRED_LATE_NIGHT_INTERACTION -> Intent(
                    type = GoalType.WIND_DOWN_FOR_NIGHT,
                    title = "Wind down for the night",
                    rootDedupeKey = "goal:wind_down:${dayKey(s.createdAtMs)}",
                    ttlMs = 4 * HOUR_MS,
                    originSituation = s.type,
                    evidence = s.evidence,
                )
                SituationType.POSSIBLE_DELIVERY_WAITING,
                SituationType.REPEATED_PATTERN_MOMENT,
                -> null  // informative-only; no goal yet
            }
            if (intent != null) out += intent
        }
        return out
    }

    private fun dayKey(ms: Long): String {
        val days = ms / 86_400_000L
        return days.toString()
    }

    companion object {
        private const val HOUR_MS = 60L * 60_000
    }
}
