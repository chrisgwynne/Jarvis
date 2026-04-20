package com.jarvis.assistant.core.decisions.triggers

import com.jarvis.assistant.core.context.AgentContext
import com.jarvis.assistant.core.decisions.Candidate
import com.jarvis.assistant.core.decisions.Trigger
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.location.LocationTransition
import com.jarvis.assistant.location.PlaceKind
import com.jarvis.assistant.proactive.ProactiveConfig
import com.jarvis.assistant.proactive.ProactiveEventType

/**
 * LocationTransitionTrigger — emits ARRIVED_HOME / LEFT_HOME / ARRIVED_KNOWN_PLACE
 * candidates from the most recent unacknowledged transition recorded by
 * [com.jarvis.assistant.location.PlaceLearner]. The source slot is cleared
 * by the proactive source after dispatch, so no repeat firing.
 */
class LocationTransitionTrigger(
    private val config: ProactiveConfig = ProactiveConfig(),
) : Trigger {
    override val id: String = "location_transition"
    override val actionClass: String = "LOCATION"

    override fun match(ctx: AgentContext, recentEvents: List<Event>): Candidate? {
        val snapshot = ctx.proactive
        val t = snapshot.lastLocationTransition ?: return null
        val place = t.place
        val dateBucket = snapshot.currentTimeMillis / (60 * 60 * 1000L)

        return when (t.placeKind) {
            PlaceKind.HOME -> when (t.kind) {
                LocationTransition.Kind.ARRIVED -> {
                    val awayMs = snapshot.currentTimeMillis - place.lastSeenAt
                    if (awayMs < config.arrivedHomeMinAwayMs) null
                    else Candidate(
                        triggerId = id,
                        eventType = ProactiveEventType.ARRIVED_HOME,
                        title = "Arrived home",
                        spokenText = "Welcome back.",
                        urgency = 0.40f,
                        relevance = 0.70f,
                        confidence = 0.90f,
                        annoyanceCost = 0.30f,
                        dedupeKey = "arrived_home_$dateBucket",
                        actionClass = actionClass,
                    )
                }
                LocationTransition.Kind.LEFT -> Candidate(
                    triggerId = id,
                    eventType = ProactiveEventType.LEFT_HOME,
                    title = "Heading out",
                    spokenText = "Heading out.",
                    urgency = 0.35f,
                    relevance = 0.55f,
                    confidence = 0.85f,
                    annoyanceCost = 0.35f,
                    dedupeKey = "left_home_$dateBucket",
                    actionClass = actionClass,
                )
            }
            PlaceKind.KNOWN -> when (t.kind) {
                LocationTransition.Kind.ARRIVED -> Candidate(
                    triggerId = id,
                    eventType = ProactiveEventType.ARRIVED_KNOWN_PLACE,
                    title = "Arrived somewhere familiar",
                    spokenText = null,
                    urgency = 0.20f,
                    relevance = 0.25f,
                    confidence = 0.70f,
                    annoyanceCost = 0.40f,
                    dedupeKey = "arrived_known_${place.latitude}_${place.longitude}_$dateBucket",
                    actionClass = actionClass,
                )
                LocationTransition.Kind.LEFT -> null
            }
            PlaceKind.UNKNOWN -> null
        }
    }
}
