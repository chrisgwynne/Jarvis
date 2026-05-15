package com.jarvis.assistant.session.context

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * A proactive suggestion Jarvis recently surfaced.
 *
 * Set by [TtsProactiveDispatcher] when a proactive message is spoken so the
 * user can follow up naturally.  Examples:
 *
 *   Jarvis: "Football starts in 30 minutes."
 *   User:   "Navigate there."  → resolves to the event location
 *
 *   Jarvis: "You have dentist at 3."
 *   User:   "Move it to tomorrow."  → resolves to that calendar event
 */
data class RecentProactiveContext(
    val eventKey: String,
    val spokenText: String,
    val entityType: ProactiveEntityType,
    /** Optional: structured data (calendar event ID, location, task ID…). */
    val entityId: String? = null,
    val entityLocation: String? = null,
    val recordedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val EXPIRY_MS = 10 * 60 * 1000L
    }

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - recordedAt > EXPIRY_MS
}

enum class ProactiveEntityType {
    CALENDAR_EVENT,
    REMINDER,
    TODOIST_TASK,
    LOCATION,
    NOTIFICATION,
    UNKNOWN,
}

class RecentProactiveContextStore {

    private val _ref = AtomicReference<RecentProactiveContext?>(null)
    private val _flow = MutableStateFlow<RecentProactiveContext?>(null)
    val contextFlow: StateFlow<RecentProactiveContext?> = _flow.asStateFlow()

    val current: RecentProactiveContext?
        get() = _ref.get()?.takeUnless { it.isExpired() }

    val hasContext: Boolean get() = current != null

    fun set(ctx: RecentProactiveContext) {
        _ref.set(ctx)
        _flow.value = ctx
    }

    fun clear() {
        _ref.set(null)
        _flow.value = null
    }
}
