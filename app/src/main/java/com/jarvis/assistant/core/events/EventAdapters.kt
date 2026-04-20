package com.jarvis.assistant.core.events

import android.util.Log

/**
 * EventAdapters — owns the lifecycle of every [EventAdapter] in one place,
 * so [com.jarvis.assistant.runtime.JarvisRuntime] has a single attach/detach
 * call instead of scattering registrations across init paths.
 *
 * Adapters are added once; [attachAll] and [detachAll] are idempotent and
 * safe to call from service restart paths.
 */
class EventAdapters(
    private val publisher: EventPublisher = EventBus,
) {
    private val adapters = mutableListOf<EventAdapter>()
    private var attached = false

    fun add(adapter: EventAdapter): EventAdapters {
        adapters.add(adapter)
        return this
    }

    fun attachAll() {
        if (attached) return
        for (a in adapters) {
            try {
                a.attach(publisher)
            } catch (e: Exception) {
                Log.w(TAG, "attach ${a.name} failed: ${e.message}")
            }
        }
        attached = true
        Log.d(TAG, "attached ${adapters.size} adapter(s)")
    }

    fun detachAll() {
        if (!attached) return
        for (a in adapters) {
            try {
                a.detach()
            } catch (e: Exception) {
                Log.w(TAG, "detach ${a.name} failed: ${e.message}")
            }
        }
        attached = false
    }

    companion object { private const val TAG = "EventAdapters" }
}
