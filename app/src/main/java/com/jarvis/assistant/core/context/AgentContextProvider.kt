package com.jarvis.assistant.core.context

import com.jarvis.assistant.context.ContextEngine
import com.jarvis.assistant.context.Presence
import com.jarvis.assistant.proactive.ContextSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * AgentContextProvider — assembles an [AgentContext] on demand by merging
 * the device, presence, and proactive slices.
 *
 * [ContextEngine] and the proactive [ContextSnapshot] builder continue to
 * own their respective source queries; this provider only glues the results
 * together, so it adds no duplicated system-service reads.
 *
 * Exposes a [StateFlow] so consumers (debug UI, future decisioning) can
 * observe the latest snapshot without re-assembling.
 */
class AgentContextProvider(
    private val contextEngine: ContextEngine,
    private val snapshotProvider: () -> ContextSnapshot,
    private val presenceProvider: () -> Presence,
) {
    private val _latest = MutableStateFlow<AgentContext?>(null)
    val latest: StateFlow<AgentContext?> = _latest

    fun current(): AgentContext {
        val device = contextEngine.build()
        val snapshot = snapshotProvider()
        val presence = presenceProvider()
        val ctx = AgentContext(
            nowMs = System.currentTimeMillis(),
            device = device,
            presence = presence,
            proactive = snapshot,
        )
        _latest.value = ctx
        return ctx
    }
}
