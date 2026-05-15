package com.jarvis.assistant.maps

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks the destination and mode of the most recently opened Maps route.
 *
 * Written by [com.jarvis.assistant.tools.device.NavigateTool] and
 * [com.jarvis.assistant.tools.device.DirectionsTool] on every successful
 * Maps handoff.
 *
 * Read by [com.jarvis.assistant.tools.device.MapsNavigationFollowupTool] to
 * resolve follow-up commands like "start it", "start driving", "switch to
 * walking" without requiring the user to repeat the destination.
 *
 * Context expires after 30 minutes so a stale destination is never
 * accidentally re-launched.
 */
data class MapsNavigationContext(
    val destination: String,
    val mode: TravelMode,
    val mapsPackage: String = MapsIntentHandler.MAPS_PACKAGE,
    val routeLoadedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = routeLoadedAt + 30 * 60 * 1000L,
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs > expiresAt
}

class MapsNavigationContextStore {

    companion object {
        private const val TAG = "MapsNavCtxStore"
    }

    private val _current = AtomicReference<MapsNavigationContext?>(null)
    private val _flow    = MutableStateFlow<MapsNavigationContext?>(null)

    val contextFlow: StateFlow<MapsNavigationContext?> = _flow.asStateFlow()

    val current: MapsNavigationContext?
        get() = _current.get()?.takeUnless { it.isExpired() }

    val hasContext: Boolean get() = current != null

    fun update(ctx: MapsNavigationContext) {
        _current.set(ctx)
        _flow.value = ctx
        Log.d(TAG, "[MAPS_ROUTE_CONTEXT_SET] dest=${ctx.destination} mode=${ctx.mode}")
    }

    fun clear() {
        _current.set(null)
        _flow.value = null
        Log.d(TAG, "[MAPS_NAV_CTX_CLEARED]")
    }
}
