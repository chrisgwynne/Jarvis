package com.jarvis.assistant.core.decisions.triggers

import com.jarvis.assistant.ambient.AmbientContextScorer
import com.jarvis.assistant.core.context.AgentContext
import com.jarvis.assistant.core.decisions.Candidate
import com.jarvis.assistant.core.decisions.Trigger
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.proactive.ProactiveEventType

/**
 * AmbientLocationTodoistTrigger — fires when the user is near a shop and
 * has Todoist tasks that match that location context.
 *
 * Example: "You're near Tesco. Milk is still on your list."
 *
 * The [com.jarvis.assistant.ambient.AmbientProactiveEventEmitter] is
 * responsible for populating [AmbientContext.nearShopName] and
 * [AmbientContext.todoistItemsMatchingLocation].
 */
class AmbientLocationTodoistTrigger : Trigger {
    override val id: String = "ambient_location_todoist"
    override val actionClass: String = "AMBIENT_LOCATION"

    override fun match(ctx: AgentContext, recentEvents: List<Event>): Candidate? {
        val shopName = ctx.ambient.nearShopName ?: return null
        val items    = ctx.ambient.todoistItemsMatchingLocation
        if (items.isEmpty()) return null

        val itemLabel = when (items.size) {
            1    -> items[0]
            2    -> "${items[0]} and ${items[1]}"
            else -> "${items[0]}, ${items[1]}, and ${items.size - 2} more"
        }
        val spokenText = "You're near $shopName. $itemLabel ${if (items.size == 1) "is" else "are"} still on your list."

        val (urgency, annoyance) = AmbientContextScorer.toScores(
            AmbientContextScorer.score(confidence = 0.70f)
        )

        return Candidate(
            triggerId     = id,
            eventType     = ProactiveEventType.AMBIENT_LOCATION_TODOIST_MATCH,
            title         = "Near $shopName — tasks on list",
            spokenText    = spokenText,
            urgency       = urgency,
            relevance     = 0.85f,
            confidence    = 0.70f,
            annoyanceCost = annoyance,
            dedupeKey     = "ambient_location_todoist_${shopName}_${ctx.nowMs / (60 * 60_000L)}",
            actionClass   = actionClass,
            metadata      = mapOf("shop" to shopName, "items" to items.joinToString(",")),
        )
    }
}
