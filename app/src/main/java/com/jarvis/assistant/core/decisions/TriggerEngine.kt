package com.jarvis.assistant.core.decisions

import android.util.Log
import com.jarvis.assistant.core.context.AgentContext
import com.jarvis.assistant.core.events.Event

/**
 * TriggerEngine — runs every registered [Trigger] against the current
 * [AgentContext] and the recent-event window, and returns the resulting
 * candidates.
 *
 * Ordering is not significant here — scoring and gating happen downstream
 * in the policy engine. Exceptions from one trigger must not break the
 * batch; they are logged and the trigger is skipped for this cycle.
 */
class TriggerEngine(
    private val triggers: List<Trigger>,
) {
    fun evaluate(ctx: AgentContext, recentEvents: List<Event>): List<Candidate> {
        val out = ArrayList<Candidate>(triggers.size)
        for (t in triggers) {
            if (!t.enabled) continue
            try {
                val c = t.match(ctx, recentEvents)
                if (c != null) out.add(c)
            } catch (e: Exception) {
                Log.w(TAG, "trigger ${t.id} threw: ${e.message}")
            }
        }
        return out
    }

    companion object { private const val TAG = "TriggerEngine" }
}
