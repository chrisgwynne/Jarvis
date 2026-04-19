package com.jarvis.assistant.core.decisions.triggers

import com.jarvis.assistant.core.context.AgentContext
import com.jarvis.assistant.core.decisions.Candidate
import com.jarvis.assistant.core.decisions.Trigger
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.proactive.ProactiveEventType

/**
 * MissedCallTrigger — port of EventGenerator.generateMissedCallEvent.
 * Relevance decays with age; urgency is fixed. [actionClass]=CALL so a
 * return call via the reactive path suppresses the proactive nudge.
 */
class MissedCallTrigger : Trigger {
    override val id: String = "missed_call"
    override val actionClass: String = "CALL"

    override fun match(ctx: AgentContext, recentEvents: List<Event>): Candidate? {
        val snapshot = ctx.proactive
        if (snapshot.missedCallsCount <= 0) return null
        val lastCallAt = snapshot.lastMissedCallAtMillis ?: return null

        val ageMs = snapshot.currentTimeMillis - lastCallAt
        val relevance = when {
            ageMs < 5 * 60_000L -> 0.90f
            ageMs < 30 * 60_000L -> 0.65f
            else -> 0.35f
        }

        val caller = snapshot.lastMissedCallContactName?.takeIf { it.isNotBlank() }
        val count = snapshot.missedCallsCount
        val spokenText = when {
            count == 1 && caller != null -> "$caller called."
            count == 1 -> "You missed a call."
            caller != null -> "$count missed calls — $caller tried you."
            else -> "$count missed calls."
        }
        val title = when {
            count == 1 && caller != null -> "Missed call — $caller"
            count == 1 -> "Missed call"
            else -> "$count missed calls"
        }
        val bucketKey = lastCallAt / 1_000L * 1_000L

        return Candidate(
            triggerId = id,
            eventType = ProactiveEventType.MISSED_CALL,
            title = title,
            spokenText = spokenText,
            urgency = 0.65f,
            relevance = relevance,
            confidence = 1.0f,
            annoyanceCost = 0.35f,
            dedupeKey = "missed_call_$bucketKey",
            actionClass = actionClass,
            metadata = buildMap {
                put("missedCallsCount", count.toString())
                if (caller != null) put("contactName", caller)
            },
        )
    }
}
