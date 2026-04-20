package com.jarvis.assistant.core.decisions.triggers

import com.jarvis.assistant.core.context.AgentContext
import com.jarvis.assistant.core.decisions.Candidate
import com.jarvis.assistant.core.decisions.Trigger
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.proactive.ProactiveConfig
import com.jarvis.assistant.proactive.ProactiveEventType

class MeetingStartingSoonTrigger(
    private val config: ProactiveConfig = ProactiveConfig(),
) : Trigger {
    override val id: String = "meeting_starting_soon"
    override val actionClass: String = "CALENDAR"

    override fun match(ctx: AgentContext, recentEvents: List<Event>): Candidate? {
        val snapshot = ctx.proactive
        val startMs = snapshot.nextMeetingAtMillis ?: return null
        val diffMs = startMs - snapshot.currentTimeMillis
        if (diffMs > config.meetingUrgentMs || diffMs < -30_000L) return null

        val title = snapshot.nextMeetingTitle?.takeIf { it.isNotBlank() }
        val spokenText = when {
            title != null && diffMs <= 60_000L -> "$title starting now."
            title != null -> "$title in a minute."
            diffMs <= 60_000L -> "Your meeting's starting."
            else -> "Meeting in a minute."
        }
        val titleLabel = if (title != null) "$title starting" else "Meeting starting"
        val bucketKey = startMs / 60_000L * 60_000L

        return Candidate(
            triggerId = id,
            eventType = ProactiveEventType.MEETING_STARTING_SOON,
            title = titleLabel,
            spokenText = spokenText,
            urgency = 0.92f,
            relevance = 0.95f,
            confidence = 1.0f,
            annoyanceCost = 0.15f,
            dedupeKey = "meeting_soon_$bucketKey",
            actionClass = actionClass,
            metadata = buildMap {
                put("nextMeetingAtMillis", startMs.toString())
                if (title != null) put("title", title)
            },
        )
    }
}
