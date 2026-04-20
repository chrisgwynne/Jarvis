package com.jarvis.assistant.core.decisions.triggers

import com.jarvis.assistant.core.context.AgentContext
import com.jarvis.assistant.core.decisions.Candidate
import com.jarvis.assistant.core.decisions.Trigger
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.proactive.ProactiveConfig
import com.jarvis.assistant.proactive.ProactiveEventType

/**
 * UpcomingMeetingTrigger — port of EventGenerator.generateUpcomingMeetingEvent.
 * The "meeting starting soon" imminent-case is a separate trigger kept
 * distinct on purpose: it bypasses quiet-hours and presence gating
 * downstream, and the DecisionEngine inspects the triggerId to do that.
 */
class UpcomingMeetingTrigger(
    private val config: ProactiveConfig = ProactiveConfig(),
) : Trigger {
    override val id: String = "upcoming_meeting"
    override val actionClass: String = "CALENDAR"

    override fun match(ctx: AgentContext, recentEvents: List<Event>): Candidate? {
        val snapshot = ctx.proactive
        val startMs = snapshot.nextMeetingAtMillis ?: return null
        val diffMs = startMs - snapshot.currentTimeMillis
        if (diffMs <= config.meetingUrgentMs || diffMs > config.meetingWindowMs) return null

        val minutesAway = (diffMs / 60_000L).coerceAtLeast(1L)
        val (urgency, relevance) = if (minutesAway <= 5L) 0.80f to 0.85f else 0.55f to 0.70f
        val title = snapshot.nextMeetingTitle?.takeIf { it.isNotBlank() }
        val spokenText = when {
            title != null -> "$title in $minutesAway minutes."
            minutesAway <= 5L -> "Meeting in $minutesAway minutes."
            else -> "A meeting in $minutesAway minutes."
        }
        val titleLabel = if (title != null) "$title in $minutesAway min" else "Meeting in $minutesAway min"
        val bucketKey = startMs / 60_000L * 60_000L

        return Candidate(
            triggerId = id,
            eventType = ProactiveEventType.UPCOMING_MEETING,
            title = titleLabel,
            spokenText = spokenText,
            urgency = urgency,
            relevance = relevance,
            confidence = 1.0f,
            annoyanceCost = 0.25f,
            dedupeKey = "upcoming_meeting_$bucketKey",
            actionClass = actionClass,
            metadata = buildMap {
                put("nextMeetingAtMillis", startMs.toString())
                if (title != null) put("title", title)
            },
        )
    }
}
