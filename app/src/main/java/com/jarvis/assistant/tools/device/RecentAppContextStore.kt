package com.jarvis.assistant.tools.device

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks the most recent app Jarvis opened or interacted with.
 *
 * Written by [OpenAppTool] on every successful launch.
 * Read by [CloseAppTool] for "close it" / "close that" follow-ups.
 *
 * Context expires after [RecentAppContext.EXPIRY_MS] (10 minutes) so a
 * "close it" spoken the next day doesn't target yesterday's app.
 */
data class RecentAppContext(
    val appName: String,
    val packageName: String?,
    val openedByJarvis: Boolean = true,
    val openedAtMs: Long = System.currentTimeMillis(),
    val lastAction: String = "open",
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - openedAtMs > EXPIRY_MS

    companion object {
        const val EXPIRY_MS = 10 * 60 * 1000L
    }
}

class RecentAppContextStore {

    companion object {
        private const val TAG = "RecentAppContextStore"
    }

    private val _current = AtomicReference<RecentAppContext?>(null)
    private val _flow    = MutableStateFlow<RecentAppContext?>(null)

    val contextFlow: StateFlow<RecentAppContext?> = _flow.asStateFlow()

    val current: RecentAppContext?
        get() = _current.get()?.takeUnless { it.isExpired() }

    val hasContext: Boolean get() = current != null

    fun set(ctx: RecentAppContext) {
        _current.set(ctx)
        _flow.value = ctx
        Log.d(TAG, "[RECENT_APP_SET] app=${ctx.appName} pkg=${ctx.packageName}")
    }

    fun clear() {
        _current.set(null)
        _flow.value = null
        Log.d(TAG, "[RECENT_APP_CLEARED]")
    }
}
