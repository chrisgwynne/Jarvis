package com.jarvis.assistant.session.context

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

enum class MessageChannel { SMS, WHATSAPP, EMAIL, NOTIFICATION }

/**
 * A message thread recently read aloud by Jarvis.
 *
 * Set by [ReadSmsTool] / [ReadNotificationsTool] after reading so follow-up
 * phrases like "reply yes" or "reply with I'm on my way" have a thread to
 * send to.
 */
data class RecentMessageContext(
    val sender: String,
    val senderNumber: String? = null,
    val body: String,
    val channel: MessageChannel,
    val threadId: Long? = null,
    val recordedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val EXPIRY_MS = 5 * 60 * 1000L
    }

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - recordedAt > EXPIRY_MS
}

class RecentMessageContextStore {

    private val _ref = AtomicReference<RecentMessageContext?>(null)
    private val _flow = MutableStateFlow<RecentMessageContext?>(null)
    val contextFlow: StateFlow<RecentMessageContext?> = _flow.asStateFlow()

    val current: RecentMessageContext?
        get() = _ref.get()?.takeUnless { it.isExpired() }

    val hasContext: Boolean get() = current != null

    fun set(ctx: RecentMessageContext) {
        _ref.set(ctx)
        _flow.value = ctx
    }

    fun clear() {
        _ref.set(null)
        _flow.value = null
    }
}
