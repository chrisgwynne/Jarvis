package com.jarvis.assistant.session.context

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * A Home Assistant entity recently controlled or queried by Jarvis.
 *
 * Set by [SmartHomeTool] after a successful command so follow-up phrases like
 * "turn it off" or "switch it on" can resolve to the same entity.
 */
data class RecentHomeAssistantContext(
    val entityId: String,
    val friendlyName: String,
    val domain: String,
    val lastAction: String,   // "on" | "off" | "toggle" | "status"
    val recordedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val EXPIRY_MS = 5 * 60 * 1000L
    }

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - recordedAt > EXPIRY_MS

    /** The opposite of the last action — used to resolve "turn it off" after "turn it on". */
    val oppositeAction: String get() = if (lastAction == "on") "off" else "on"
}

class RecentHomeAssistantContextStore {

    private val _ref = AtomicReference<RecentHomeAssistantContext?>(null)
    private val _flow = MutableStateFlow<RecentHomeAssistantContext?>(null)
    val contextFlow: StateFlow<RecentHomeAssistantContext?> = _flow.asStateFlow()

    val current: RecentHomeAssistantContext?
        get() = _ref.get()?.takeUnless { it.isExpired() }

    val hasContext: Boolean get() = current != null

    fun set(ctx: RecentHomeAssistantContext) {
        _ref.set(ctx)
        _flow.value = ctx
    }

    fun clear() {
        _ref.set(null)
        _flow.value = null
    }
}
