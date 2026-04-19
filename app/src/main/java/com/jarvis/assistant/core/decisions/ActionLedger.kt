package com.jarvis.assistant.core.decisions

import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.core.events.EventKind
import com.jarvis.assistant.core.events.EventPublisher
import com.jarvis.assistant.proactive.CooldownStore
import java.util.concurrent.ConcurrentHashMap

/**
 * ActionLedger — cross-path record of what the agent has already done, what
 * it has already surfaced, and what the user has signalled about it.
 *
 * Solves the "user voice-dismisses, proactive re-fires" fragmentation
 * identified in the audit. Both the proactive path ([com.jarvis.assistant
 * .proactive.ProactiveEngine]) and the reactive path ([com.jarvis.assistant
 * .runtime.ToolDispatcher]) report here; both can query it.
 *
 * Cooldown state is delegated to [CooldownStore] so there is exactly one
 * source of truth for dedupe keys. This class adds the action-class layer
 * on top: a proactive LOW_BATTERY suggestion and a voice "set battery
 * saver" both share the action class `BATTERY` and suppress each other.
 */
class ActionLedger(
    private val cooldownStore: CooldownStore,
    private val publisher: EventPublisher? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val lastActionClassMs = ConcurrentHashMap<String, Long>()
    private val lastToolCallMs = ConcurrentHashMap<String, Long>()

    fun recordProactiveDispatch(dedupeKey: String, actionClass: String?) {
        cooldownStore.markSurfaced(dedupeKey)
        val now = nowMs()
        if (actionClass != null) lastActionClassMs[actionClass] = now
        publisher?.publish(
            Event.of(
                kind = EventKind.PROACTIVE_DISPATCHED,
                source = "ActionLedger",
                payload = mapOf(
                    "dedupe_key" to dedupeKey,
                    "action_class" to (actionClass ?: ""),
                ),
                sensitivity = Event.Sensitivity.PUBLIC,
                dedupeKey = dedupeKey,
            )
        )
    }

    fun recordToolExecution(toolName: String, actionClass: String?) {
        val now = nowMs()
        lastToolCallMs[toolName] = now
        if (actionClass != null) lastActionClassMs[actionClass] = now
        publisher?.publish(
            Event.of(
                kind = EventKind.TOOL_EXECUTED,
                source = "ActionLedger",
                payload = mapOf(
                    "tool" to toolName,
                    "action_class" to (actionClass ?: ""),
                ),
                sensitivity = Event.Sensitivity.PUBLIC,
            )
        )
    }

    fun recordVerdict(dedupeKey: String, accepted: Boolean) {
        if (accepted) cooldownStore.markAccepted(dedupeKey)
        else cooldownStore.markIgnored(dedupeKey)
        publisher?.publish(
            Event.of(
                kind = EventKind.USER_VERDICT,
                source = "ActionLedger",
                payload = mapOf(
                    "dedupe_key" to dedupeKey,
                    "accepted" to accepted.toString(),
                ),
                sensitivity = Event.Sensitivity.PUBLIC,
                dedupeKey = dedupeKey,
            )
        )
    }

    fun msSinceSurfaced(dedupeKey: String): Long = cooldownStore.msSinceSurfaced(dedupeKey)
    fun msSinceLastGlobalSurface(): Long = cooldownStore.msSinceLastGlobalSurface()
    fun ignoreCount(dedupeKey: String): Int = cooldownStore.ignoreCount(dedupeKey)
    fun isOnCooldown(dedupeKey: String, cooldownMs: Long): Boolean =
        cooldownStore.isOnCooldown(dedupeKey, cooldownMs)

    fun msSinceActionClass(actionClass: String): Long {
        val last = lastActionClassMs[actionClass] ?: return Long.MAX_VALUE
        return nowMs() - last
    }

    fun msSinceToolCall(toolName: String): Long {
        val last = lastToolCallMs[toolName] ?: return Long.MAX_VALUE
        return nowMs() - last
    }
}
