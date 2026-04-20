package com.jarvis.assistant.core.decisions.triggers

import com.jarvis.assistant.core.context.AgentContext
import com.jarvis.assistant.core.decisions.Candidate
import com.jarvis.assistant.core.decisions.Trigger
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.core.events.EventKind
import com.jarvis.assistant.core.learning.KnownSsidStore
import com.jarvis.assistant.proactive.ProactiveEventType

/**
 * UnfamiliarSsidTrigger — fires once when [EventKind.WIFI_SSID_CHANGED]
 * lands on an SSID the user has never been promoted to "known".
 *
 * Every observation goes to [KnownSsidStore.observe], so a new network the
 * user actually uses more than once gets promoted silently. The trigger
 * only speaks up on the *first* sighting of a truly novel SSID.
 *
 * Shares the BEHAVIORAL_LEARNING ProactiveEventType so the cooldown and
 * scoring pipeline treats it like any other soft observation.
 */
class UnfamiliarSsidTrigger(
    private val knownSsidStore: KnownSsidStore,
) : Trigger {
    override val id: String = "unfamiliar_ssid"
    override val actionClass: String = "NETWORK"

    override fun match(ctx: AgentContext, recentEvents: List<Event>): Candidate? {
        val ssidEvent = recentEvents.firstOrNull { it.kind == EventKind.WIFI_SSID_CHANGED } ?: return null
        val ssid = ssidEvent.payload["ssid"]?.takeIf { it.isNotBlank() } ?: return null

        val alreadyKnown = knownSsidStore.isKnown(ssid)
        knownSsidStore.observe(ssid)
        if (alreadyKnown) return null

        return Candidate(
            triggerId = id,
            eventType = ProactiveEventType.BEHAVIORAL_LEARNING,
            title = "New Wi-Fi network",
            spokenText = "New Wi-Fi — ${ssid}. Want to name it?",
            urgency = 0.35f,
            relevance = 0.55f,
            confidence = 0.90f,
            annoyanceCost = 0.35f,
            dedupeKey = "unfamiliar_ssid_$ssid",
            actionClass = actionClass,
            metadata = mapOf("ssid" to ssid),
        )
    }
}
