package com.jarvis.assistant.session.context

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * A Todoist task recently mentioned, created, or read by Jarvis.
 *
 * Set by Todoist tools so follow-up phrases like "mark it done", "make it
 * urgent", or "move that to tomorrow" resolve against the right task.
 */
data class RecentTodoistContext(
    val taskId: String,
    val taskContent: String,
    val projectId: String? = null,
    val projectName: String? = null,
    val lastAction: String = "created",
    val recordedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val EXPIRY_MS = 5 * 60 * 1000L
    }

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - recordedAt > EXPIRY_MS
}

class RecentTodoistContextStore {

    private val _ref = AtomicReference<RecentTodoistContext?>(null)
    private val _flow = MutableStateFlow<RecentTodoistContext?>(null)
    val contextFlow: StateFlow<RecentTodoistContext?> = _flow.asStateFlow()

    val current: RecentTodoistContext?
        get() = _ref.get()?.takeUnless { it.isExpired() }

    val hasContext: Boolean get() = current != null

    fun set(ctx: RecentTodoistContext) {
        _ref.set(ctx)
        _flow.value = ctx
    }

    fun clear() {
        _ref.set(null)
        _flow.value = null
    }
}
