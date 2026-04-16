package com.jarvis.assistant.call

import kotlinx.coroutines.flow.SharedFlow

/**
 * CallMonitor — observes telephony state and emits structured [CallEvent]s.
 *
 * Production implementation: [TelephonyCallMonitor]
 *
 * Extension point: a VoIP monitor (WhatsApp, Teams, etc.) can implement this
 * interface without changing [CallCoordinator] or [JarvisRuntime].
 *
 * Contract:
 *  • [events] is a hot SharedFlow; late subscribers will not receive stale events.
 *  • [start] is idempotent — calling it twice has no effect.
 *  • [stop] is idempotent and safe to call without a preceding [start].
 *  • Implementations must check required permissions before registering and log
 *    a warning (not throw) if they are missing.
 */
interface CallMonitor {

    /**
     * Hot SharedFlow of call domain events.
     * Collect from a coroutine on the Main dispatcher in [JarvisRuntime].
     */
    val events: SharedFlow<CallEvent>

    /**
     * Register telephony listeners and begin emitting events.
     * Requires READ_PHONE_STATE — degrades gracefully if missing.
     */
    fun start()

    /** Unregister telephony listeners and release all telephony resources. */
    fun stop()
}
