package com.jarvis.assistant.core.decisions.triggers

import com.jarvis.assistant.ambient.AmbientContextScorer
import com.jarvis.assistant.core.context.AgentContext
import com.jarvis.assistant.core.decisions.Candidate
import com.jarvis.assistant.core.decisions.Trigger
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.proactive.ProactiveEventType

/**
 * AmbientCalendarTravelTrigger — uses learned routine patterns to suggest
 * the user should start heading to a calendar event.
 *
 * Example: "You normally leave for football in 15 minutes."
 *
 * Fires when the event is within [TRIGGER_WINDOW_MINUTES] of the learned
 * leave-time. Falls back to a default 20-minute lead when no pattern exists.
 */
class AmbientCalendarTravelTrigger : Trigger {
    override val id: String = "ambient_calendar_travel"
    override val actionClass: String = "AMBIENT_TRAVEL"

    override fun match(ctx: AgentContext, recentEvents: List<Event>): Candidate? {
        val nextMeetingMs = ctx.proactive.nextMeetingAtMillis ?: return null
        val minutesUntil = (nextMeetingMs - ctx.nowMs) / 60_000L
        if (minutesUntil < 0) return null

        val leaveLeadMinutes: Long = (ctx.ambient.learnedLeaveLeadMinutes ?: DEFAULT_LEAD_MINUTES_INT).toLong()
        val targetLeaveMinutes = minutesUntil - leaveLeadMinutes

        // Fire when it's time to leave (within the trigger window)
        if (targetLeaveMinutes !in -TRIGGER_WINDOW_MINUTES..TRIGGER_WINDOW_MINUTES) return null

        val eventTitle = ctx.proactive.nextMeetingTitle ?: "your next event"
        val spokenText = if (targetLeaveMinutes <= 0) {
            "Time to leave for $eventTitle."
        } else {
            "You normally leave for $eventTitle in $targetLeaveMinutes minutes."
        }

        val confidence = if (ctx.ambient.learnedLeaveLeadMinutes != null) 0.75f else 0.55f
        val (urgency, annoyance) = AmbientContextScorer.toScores(
            AmbientContextScorer.score(confidence = confidence)
        )

        return Candidate(
            triggerId     = id,
            eventType     = ProactiveEventType.AMBIENT_ROUTINE_SUGGESTION,
            title         = "Leave for event soon",
            spokenText    = spokenText,
            urgency       = urgency,
            relevance     = 0.80f,
            confidence    = confidence,
            annoyanceCost = annoyance,
            dedupeKey     = "ambient_travel_${nextMeetingMs / (60 * 60_000L)}",
            actionClass   = actionClass,
            metadata      = mapOf(
                "event"         to eventTitle,
                "minutes_until" to minutesUntil.toString(),
                "leave_lead"    to leaveLeadMinutes.toString(),
            ),
        )
    }

    companion object {
        private const val DEFAULT_LEAD_MINUTES_INT = 20
        private const val TRIGGER_WINDOW_MINUTES = 5L
    }
}
