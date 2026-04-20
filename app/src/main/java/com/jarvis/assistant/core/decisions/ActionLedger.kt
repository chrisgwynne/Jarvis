package com.jarvis.assistant.core.decisions

import android.content.Context
import android.content.SharedPreferences
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
    /** Optional persistent store for per-class accept/ignore counters. */
    private val prefs: SharedPreferences? = null,
) {
    private val lastActionClassMs = ConcurrentHashMap<String, Long>()
    private val lastToolCallMs = ConcurrentHashMap<String, Long>()
    private val classAccepts = ConcurrentHashMap<String, Int>()
    private val classIgnores = ConcurrentHashMap<String, Int>()

    init { hydrateCounters() }

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

    fun recordVerdict(dedupeKey: String, accepted: Boolean, actionClass: String? = null) {
        if (accepted) cooldownStore.markAccepted(dedupeKey)
        else cooldownStore.markIgnored(dedupeKey)
        if (actionClass != null) {
            val map = if (accepted) classAccepts else classIgnores
            map.merge(actionClass, 1) { old, _ -> old + 1 }
            persistCounter(actionClass)
        }
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

    /**
     * Per-class accept rate in [0, 1]. Returns 0.5 (neutral prior) until
     * [minSamples] verdicts have been recorded, so newly-introduced classes
     * aren't penalised before they have history. Used by [EventScorer] to
     * adjust annoyance cost for consistently-ignored action classes.
     */
    fun acceptRate(actionClass: String, minSamples: Int = 4): Float {
        val accepts = classAccepts[actionClass] ?: 0
        val ignores = classIgnores[actionClass] ?: 0
        val total = accepts + ignores
        if (total < minSamples) return 0.5f
        return accepts.toFloat() / total.toFloat()
    }

    fun verdictCount(actionClass: String): Int =
        (classAccepts[actionClass] ?: 0) + (classIgnores[actionClass] ?: 0)

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun hydrateCounters() {
        val p = prefs ?: return
        for ((key, value) in p.all) {
            val raw = value as? Int ?: continue
            when {
                key.startsWith(PREF_ACCEPT_PREFIX) -> classAccepts[key.removePrefix(PREF_ACCEPT_PREFIX)] = raw
                key.startsWith(PREF_IGNORE_PREFIX) -> classIgnores[key.removePrefix(PREF_IGNORE_PREFIX)] = raw
            }
        }
    }

    private fun persistCounter(actionClass: String) {
        val p = prefs ?: return
        val accepts = classAccepts[actionClass] ?: 0
        val ignores = classIgnores[actionClass] ?: 0
        p.edit()
            .putInt(PREF_ACCEPT_PREFIX + actionClass, accepts)
            .putInt(PREF_IGNORE_PREFIX + actionClass, ignores)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "jarvis_action_ledger"
        private const val PREF_ACCEPT_PREFIX = "accepts_"
        private const val PREF_IGNORE_PREFIX = "ignores_"

        fun prefsFor(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
