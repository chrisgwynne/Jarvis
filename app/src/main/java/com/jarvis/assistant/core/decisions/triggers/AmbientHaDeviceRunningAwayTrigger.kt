package com.jarvis.assistant.core.decisions.triggers

import com.jarvis.assistant.ambient.AmbientContextScorer
import com.jarvis.assistant.ambient.AmbientLocationBucket
import com.jarvis.assistant.core.context.AgentContext
import com.jarvis.assistant.core.decisions.Candidate
import com.jarvis.assistant.core.decisions.Trigger
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.proactive.ProactiveEventType

/**
 * AmbientHaDeviceRunningAwayTrigger — alerts when a Home Assistant device
 * (printer, workshop socket, oven etc.) is detected as running while the
 * user is away from home.
 *
 * Example: "The printer's still running and nobody's home."
 *
 * Complements [HomeAssistantMotionAwayTrigger] (which handles motion/presence
 * sensors); this trigger focuses on active device states.
 */
class AmbientHaDeviceRunningAwayTrigger : Trigger {
    override val id: String = "ambient_ha_device_running_away"
    override val actionClass: String = "AMBIENT_HA_ALERT"

    override fun match(ctx: AgentContext, recentEvents: List<Event>): Candidate? {
        val devices = ctx.ambient.haDevicesRunningAway
        if (devices.isEmpty()) return null

        // Only fire when we're confident the user is not home
        val awayConfident = ctx.ambient.locationBucket != AmbientLocationBucket.HOME &&
            ctx.ambient.locationBucket != AmbientLocationBucket.UNKNOWN

        // Also check location transition from ContextSnapshot as a fallback
        val transition = ctx.proactive.lastLocationTransition
        val leftHome = transition?.kind?.name == "LEFT" && transition.placeKind.name == "HOME"

        if (!awayConfident && !leftHome) return null

        val deviceList = when (devices.size) {
            1    -> devices[0]
            2    -> "${devices[0]} and ${devices[1]}"
            else -> "${devices.take(2).joinToString(", ")} and ${devices.size - 2} more"
        }
        val verb = if (devices.size == 1) "is" else "are"
        val spokenText = "$deviceList $verb still running and nobody's home."

        val (urgency, annoyance) = AmbientContextScorer.toScores(
            AmbientContextScorer.score(confidence = 0.85f, isUncertain = !awayConfident)
        )

        return Candidate(
            triggerId     = id,
            eventType     = ProactiveEventType.AMBIENT_HOME_ASSISTANT_ALERT,
            title         = "HA device running while away",
            spokenText    = spokenText,
            urgency       = urgency.coerceAtLeast(0.60f),
            relevance     = 0.90f,
            confidence    = 0.85f,
            annoyanceCost = annoyance,
            dedupeKey     = "ambient_ha_away_${ctx.nowMs / (60 * 60_000L)}",
            actionClass   = actionClass,
            metadata      = mapOf("devices" to devices.joinToString(",")),
        )
    }
}
