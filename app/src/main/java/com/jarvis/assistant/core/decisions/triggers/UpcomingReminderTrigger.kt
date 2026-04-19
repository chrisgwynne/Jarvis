package com.jarvis.assistant.core.decisions.triggers

import com.jarvis.assistant.core.context.AgentContext
import com.jarvis.assistant.core.decisions.Candidate
import com.jarvis.assistant.core.decisions.Trigger
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.proactive.ProactiveConfig
import com.jarvis.assistant.proactive.ProactiveEventType

class UpcomingReminderTrigger(
    private val config: ProactiveConfig = ProactiveConfig(),
) : Trigger {
    override val id: String = "upcoming_reminder"
    override val actionClass: String = "REMINDER"

    override fun match(ctx: AgentContext, recentEvents: List<Event>): Candidate? {
        val snapshot = ctx.proactive
        val nextMs = snapshot.nextReminderAtMillis ?: return null
        val diffMs = nextMs - snapshot.currentTimeMillis
        if (diffMs > config.reminderWindowMs || diffMs < 0) return null

        val (urgency, relevance) = when {
            diffMs <= config.reminderUrgentMs -> 0.90f to 0.95f
            diffMs <= config.reminderHighWindowMs -> 0.70f to 0.80f
            else -> 0.50f to 0.60f
        }

        val minutesAway = (diffMs / 60_000L).coerceAtLeast(1L)
        val spokenText = when {
            minutesAway <= 1L -> "You've got something coming up any minute."
            minutesAway < 10L -> "You've got something in about $minutesAway minutes."
            else -> "Reminder in about $minutesAway minutes."
        }
        val titleLabel = if (minutesAway <= 1L) "in a minute" else "in $minutesAway min"
        val bucketKey = nextMs / 60_000L * 60_000L

        return Candidate(
            triggerId = id,
            eventType = ProactiveEventType.UPCOMING_REMINDER,
            title = "Reminder $titleLabel",
            spokenText = spokenText,
            urgency = urgency,
            relevance = relevance,
            confidence = 1.0f,
            annoyanceCost = 0.20f,
            dedupeKey = "upcoming_reminder_$bucketKey",
            actionClass = actionClass,
            metadata = mapOf(
                "nextReminderAtMillis" to nextMs.toString(),
                "activeReminderCount" to snapshot.activeReminderCount.toString(),
            ),
        )
    }
}
