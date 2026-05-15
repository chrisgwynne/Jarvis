package com.jarvis.assistant.core.decisions.triggers

import com.jarvis.assistant.ambient.AmbientContextScorer
import com.jarvis.assistant.core.context.AgentContext
import com.jarvis.assistant.core.decisions.Candidate
import com.jarvis.assistant.core.decisions.Trigger
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.proactive.ProactiveEventType

/**
 * AmbientCarBluetoothTrigger — fires when the user's car Bluetooth connects
 * and there is an upcoming calendar event within the next few hours.
 *
 * Example: "Want directions to football?"
 */
class AmbientCarBluetoothTrigger : Trigger {
    override val id: String = "ambient_car_bluetooth"
    override val actionClass: String = "AMBIENT_TRAVEL"

    override fun match(ctx: AgentContext, recentEvents: List<Event>): Candidate? {
        val ambient = ctx.ambient
        val connectedMs = ambient.carBtConnectedMs ?: return null

        // Only fire in the window just after connecting, not indefinitely.
        if (ctx.nowMs - connectedMs > RECENCY_MS) return null

        val nextMeetingMs = ctx.proactive.nextMeetingAtMillis ?: return null
        val minutesUntil = (nextMeetingMs - ctx.nowMs) / 60_000L
        if (minutesUntil < 0 || minutesUntil > MAX_LOOK_AHEAD_MINUTES) return null

        val title = ctx.proactive.nextMeetingTitle?.let { " to ${it.take(30)}" } ?: ""
        val timeLabel = when {
            minutesUntil < 5   -> "now"
            minutesUntil < 60  -> "in $minutesUntil minutes"
            else               -> "in about ${minutesUntil / 60} hours"
        }
        val spokenText = "You've got ${ctx.proactive.nextMeetingTitle ?: "something"} $timeLabel. Want directions?"

        val (urgency, annoyance) = AmbientContextScorer.toScores(
            AmbientContextScorer.score(confidence = 0.80f)
        )

        return Candidate(
            triggerId     = id,
            eventType     = ProactiveEventType.AMBIENT_TRAVEL_SUGGESTION,
            title         = "Car connected — event $timeLabel",
            spokenText    = spokenText,
            urgency       = urgency.coerceAtMost(0.75f),
            relevance     = 0.85f,
            confidence    = 0.80f,
            annoyanceCost = annoyance,
            dedupeKey     = "ambient_car_bt_${ctx.nowMs / (20 * 60_000L)}",
            actionClass   = actionClass,
            metadata      = mapOf("minutes_until" to minutesUntil.toString()),
        )
    }

    companion object {
        private const val RECENCY_MS = 5 * 60_000L
        private const val MAX_LOOK_AHEAD_MINUTES = 180L
    }
}
