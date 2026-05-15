package com.jarvis.assistant.session.context

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * A calendar event recently read or created by Jarvis.
 *
 * Set by [CalendarTool] after a successful read so follow-up phrases like
 * "move that to Friday" have something to resolve against.
 */
data class RecentCalendarContext(
    val eventId: Long? = null,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val calendarId: Long? = null,
    val recordedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val EXPIRY_MS = 5 * 60 * 1000L
    }

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - recordedAt > EXPIRY_MS
}

class RecentCalendarContextStore {

    private val _ref = AtomicReference<RecentCalendarContext?>(null)
    private val _flow = MutableStateFlow<RecentCalendarContext?>(null)
    val contextFlow: StateFlow<RecentCalendarContext?> = _flow.asStateFlow()

    val current: RecentCalendarContext?
        get() = _ref.get()?.takeUnless { it.isExpired() }

    val hasContext: Boolean get() = current != null

    fun set(ctx: RecentCalendarContext) {
        _ref.set(ctx)
        _flow.value = ctx
    }

    fun clear() {
        _ref.set(null)
        _flow.value = null
    }
}
