package com.jarvis.assistant.core.decisions.triggers

import com.jarvis.assistant.core.context.AgentContext
import com.jarvis.assistant.core.decisions.Candidate
import com.jarvis.assistant.core.decisions.Trigger
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.core.events.EventKind
import com.jarvis.assistant.location.LocationTransition
import com.jarvis.assistant.location.PlaceKind
import com.jarvis.assistant.proactive.ProactiveEventType

/**
 * HomeAssistantMotionAwayTrigger — the first inbound-smart-home composite.
 * Fires when a Home Assistant motion / binary sensor flips to "on" AND
 * the user's most recent location transition was LEFT_HOME (or they have
 * not been observed arriving at HOME recently).
 *
 * Emits at ACTIVE tier because unexpected motion while the user is out
 * is security-relevant. Dedupe keyed per entity + day so the same sensor
 * re-triggering over the same away-window doesn't spam.
 *
 * Filters to sensors whose entity id hints at front-door / porch / entry
 * to keep signal-to-noise sane. Anything else is a lower-urgency soft
 * observation that the legacy flow will still raise if it matters.
 */
class HomeAssistantMotionAwayTrigger : Trigger {
    override val id: String = "ha_motion_away"
    override val actionClass: String = "SECURITY"

    override fun match(ctx: AgentContext, recentEvents: List<Event>): Candidate? {
        val event = recentEvents
            .firstOrNull { it.kind == EventKind.SMART_HOME_STATE && isMotion(it) && isSecurityEntity(it) }
            ?: return null

        if (!userIsAway(ctx)) return null

        val friendlyName = event.payload["friendly_name"] ?: event.payload["entity_id"] ?: "a sensor"
        val entityId = event.payload["entity_id"] ?: "unknown"
        val dateBucket = ctx.nowMs / (24 * 60 * 60 * 1000L)

        return Candidate(
            triggerId = id,
            eventType = ProactiveEventType.BEHAVIORAL_LEARNING,
            title = "Motion at $friendlyName",
            spokenText = "Motion at $friendlyName while you're out. Worth a look.",
            urgency = 0.85f,
            relevance = 0.90f,
            confidence = 0.90f,
            annoyanceCost = 0.15f,
            dedupeKey = "ha_motion_away_${entityId}_$dateBucket",
            actionClass = actionClass,
            metadata = event.payload,
        )
    }

    private fun isMotion(e: Event): Boolean {
        val state = e.payload["state"] ?: return false
        if (state != "on" && state != "detected" && state != "motion") return false
        val entityId = e.payload["entity_id"].orEmpty().lowercase()
        return entityId.contains("motion") || entityId.contains("presence") || entityId.contains("occupancy")
    }

    private fun isSecurityEntity(e: Event): Boolean {
        val id = e.payload["entity_id"].orEmpty().lowercase()
        return SECURITY_HINTS.any { id.contains(it) }
    }

    private fun userIsAway(ctx: AgentContext): Boolean {
        val transition = ctx.locationTransition ?: return true
        return when (transition.placeKind) {
            PlaceKind.HOME -> transition.kind == LocationTransition.Kind.LEFT
            else -> true
        }
    }

    companion object {
        private val SECURITY_HINTS = setOf(
            "front", "door", "porch", "entry", "hall", "driveway", "garage",
            "back_door", "backdoor", "side_door",
        )
    }
}
