package com.jarvis.assistant.core.decisions.triggers

import com.jarvis.assistant.core.context.AgentContext
import com.jarvis.assistant.core.decisions.Candidate
import com.jarvis.assistant.core.decisions.Trigger
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.proactive.ProactiveConfig
import com.jarvis.assistant.proactive.ProactiveEventType

/**
 * LowBatteryBeforeTravelTrigger — the first composite trigger: fires when
 * battery is low AND the user has an outgoing meeting in the near-term
 * look-ahead. Proves the framework's value over [LowBatteryTrigger] alone,
 * which cannot express "low battery AND you're about to leave".
 *
 * Composes two signals that both exist on the proactive snapshot today:
 * battery level and the next timed meeting. When they line up we surface
 * a high-urgency suggestion. When they don't, we stay silent and let
 * [LowBatteryTrigger] handle the plain case with its lower urgency.
 *
 * Shares the LOW_BATTERY ProactiveEventType so cooldown / scoring
 * pipelines treat it consistently with the plain case, but the dedupe
 * key is separate so both cannot dispatch in the same tick window.
 */
class LowBatteryBeforeTravelTrigger(
    private val config: ProactiveConfig = ProactiveConfig(),
    /** Upper bound on "imminent travel" — default 2h so we warn in time. */
    private val travelWindowMs: Long = 2 * 60 * 60 * 1000L,
    /** Treat battery ≤ this as "low enough to act on before travel." */
    private val batteryThreshold: Int = 25,
) : Trigger {
    override val id: String = "low_battery_before_travel"
    override val actionClass: String = "BATTERY"

    override fun match(ctx: AgentContext, recentEvents: List<Event>): Candidate? {
        val snapshot = ctx.proactive
        val battery = snapshot.batteryLevel
        if (snapshot.isCharging) return null
        if (battery < 0 || battery > batteryThreshold) return null

        val nextMeetingAt = snapshot.nextMeetingAtMillis ?: return null
        val msUntilMeeting = nextMeetingAt - snapshot.currentTimeMillis
        if (msUntilMeeting <= 0 || msUntilMeeting > travelWindowMs) return null

        val minutesAway = (msUntilMeeting / 60_000L).coerceAtLeast(1L)
        val meetingTitle = snapshot.nextMeetingTitle?.takeIf { it.isNotBlank() }
        val spokenText = when {
            meetingTitle != null -> "$battery% battery and $meetingTitle in $minutesAway minutes. Plug in before you leave."
            else -> "$battery% battery and you're out in $minutesAway minutes. Worth plugging in now."
        }
        val bucket = battery / 5 * 5
        val meetingBucket = nextMeetingAt / 60_000L * 60_000L

        return Candidate(
            triggerId = id,
            eventType = ProactiveEventType.LOW_BATTERY,
            title = "Low battery before travel",
            spokenText = spokenText,
            urgency = 0.88f,
            relevance = 0.92f,
            confidence = 1.0f,
            annoyanceCost = 0.20f,
            dedupeKey = "low_battery_before_travel_${bucket}_$meetingBucket",
            actionClass = actionClass,
            metadata = buildMap {
                put("battery_pct", battery.toString())
                put("meeting_at_ms", nextMeetingAt.toString())
                if (meetingTitle != null) put("meeting_title", meetingTitle)
            },
        )
    }
}
