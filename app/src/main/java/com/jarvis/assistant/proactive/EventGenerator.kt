package com.jarvis.assistant.proactive

import com.jarvis.assistant.core.context.AgentContextFactory
import com.jarvis.assistant.core.decisions.TriggerEngine
import com.jarvis.assistant.core.decisions.triggers.DefaultTriggers
import com.jarvis.assistant.core.events.Event

/**
 * EventGenerator — thin compatibility wrapper around the new
 * [TriggerEngine]. Existing callers (tests, [ProactiveSimulator]) keep
 * working while the framework is the single source of truth.
 *
 * New proactive signals belong in [com.jarvis.assistant.core.decisions.Trigger]
 * implementations under `core/decisions/triggers/`, not as new methods here.
 */
class EventGenerator(
    private val config: ProactiveConfig,
    private val triggerEngine: TriggerEngine = DefaultTriggers.engine(config),
) {

    fun generate(
        snapshot: ContextSnapshot,
        recentEvents: List<Event> = emptyList(),
        ambient: com.jarvis.assistant.ambient.AmbientContext =
            com.jarvis.assistant.ambient.AmbientContext.EMPTY,
    ): List<ProactiveEvent> {
        val ctx = AgentContextFactory.fromSnapshot(snapshot, ambient)
        return triggerEngine.evaluate(ctx, recentEvents).map { it.toProactiveEvent() }
    }

    fun buildDailyBrief(snapshot: ContextSnapshot): Map<DailyBriefBucket, List<ProactiveEvent>> {
        val events = generate(snapshot)
        val result = mutableMapOf(
            DailyBriefBucket.NOW to mutableListOf<ProactiveEvent>(),
            DailyBriefBucket.SOON to mutableListOf(),
            DailyBriefBucket.INFO to mutableListOf(),
        )
        for (event in events) {
            val bucket = when {
                event.urgency >= 0.8f -> DailyBriefBucket.NOW
                event.urgency >= 0.5f -> DailyBriefBucket.SOON
                else -> DailyBriefBucket.INFO
            }
            result[bucket]!!.add(event)
        }
        return result
    }
}

/**
 * DailyBriefBucket — urgency tier used by [EventGenerator.buildDailyBrief].
 */
enum class DailyBriefBucket {
    NOW,
    SOON,
    INFO,
}
