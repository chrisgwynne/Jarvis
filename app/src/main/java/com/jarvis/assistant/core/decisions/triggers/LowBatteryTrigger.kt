package com.jarvis.assistant.core.decisions.triggers

import com.jarvis.assistant.core.context.AgentContext
import com.jarvis.assistant.core.decisions.Candidate
import com.jarvis.assistant.core.decisions.Trigger
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.proactive.ProactiveConfig
import com.jarvis.assistant.proactive.ProactiveEventType

/**
 * LowBatteryTrigger — direct port of EventGenerator.generateBatteryEvent.
 * Behaviour is intentionally identical so the new framework can run in
 * parallel to the legacy path until ProactiveEngine is rewired.
 */
class LowBatteryTrigger(
    private val config: ProactiveConfig = ProactiveConfig(),
) : Trigger {
    override val id: String = "low_battery"
    override val actionClass: String = "BATTERY"

    override fun match(ctx: AgentContext, recentEvents: List<Event>): Candidate? {
        val snapshot = ctx.proactive
        val battery = snapshot.batteryLevel
        if (battery > config.batteryLow || snapshot.isCharging) return null

        val urgency = when {
            battery <= config.batteryCritical -> 0.95f
            battery <= config.batteryVeryLow -> 0.80f
            else -> 0.55f
        }
        val spokenText = when {
            battery <= config.batteryCritical -> "Battery's nearly dead — $battery%."
            battery <= config.batteryVeryLow -> "Battery's getting low — $battery%."
            else -> "Battery's at $battery%."
        }
        val bucket = battery / 5 * 5

        return Candidate(
            triggerId = id,
            eventType = ProactiveEventType.LOW_BATTERY,
            title = "Battery $battery%",
            spokenText = spokenText,
            urgency = urgency,
            relevance = 0.80f,
            confidence = 1.0f,
            annoyanceCost = 0.25f,
            dedupeKey = "low_battery_$bucket",
            actionClass = actionClass,
            metadata = mapOf("batteryLevel" to battery.toString()),
        )
    }
}
