package com.jarvis.assistant.core.decisions

import com.jarvis.assistant.core.context.AgentContext
import com.jarvis.assistant.core.events.Event

/**
 * Trigger — declarative condition that, given a snapshot of [AgentContext]
 * and recent [Event]s, may produce a [Candidate] for the policy engine.
 *
 * Replaces the hand-coded private generator functions inside
 * [com.jarvis.assistant.proactive.EventGenerator]. Triggers are plain
 * classes registered with [TriggerEngine]; adding a new proactive signal
 * is one file, not edits across five files.
 *
 * [enabled] lets the runtime toggle a trigger without destroying its state
 * (user-facing "never suggest X again" flows live here).
 */
interface Trigger {
    val id: String
    val actionClass: String?
    val enabled: Boolean get() = true

    fun match(ctx: AgentContext, recentEvents: List<Event>): Candidate?
}
