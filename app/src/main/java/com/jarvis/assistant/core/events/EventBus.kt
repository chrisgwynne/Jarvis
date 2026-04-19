package com.jarvis.assistant.core.events

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter

/**
 * EventBus — single process-wide stream every sensed signal is published to.
 *
 * Uses [MutableSharedFlow] with a small replay buffer so late subscribers
 * (e.g. TriggerEngine on startup, debug UI) still see recent context.
 * Overflow on fast producers drops the oldest item rather than suspending
 * the caller — ingestion must never block.
 *
 * This is a singleton object because there is exactly one physical stream
 * of sensed signals per process. Tests can still inject a fresh instance
 * via [newTestInstance] if they construct TriggerEngine/adapters directly.
 */
object EventBus : EventPublisher {

    private const val TAG = "EventBus"
    private const val REPLAY = 32
    private const val EXTRA_BUFFER = 128

    private val flow: MutableSharedFlow<Event> = MutableSharedFlow(
        replay = REPLAY,
        extraBufferCapacity = EXTRA_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: Flow<Event> get() = flow.asSharedFlow()

    fun ofKind(kind: EventKind): Flow<Event> = flow.filter { it.kind == kind }

    fun ofKinds(vararg kinds: EventKind): Flow<Event> {
        val set = kinds.toSet()
        return flow.filter { it.kind in set }
    }

    override fun publish(event: Event) {
        val ok = flow.tryEmit(event)
        if (!ok) Log.w(TAG, "tryEmit dropped ${event.kind} from ${event.source}")
    }

    override fun publish(
        kind: EventKind,
        source: String,
        payload: Map<String, String>,
        actor: String?,
        confidence: Float,
        sensitivity: Event.Sensitivity,
        dedupeKey: String?,
    ) {
        publish(Event.of(kind, source, payload, actor, confidence, sensitivity, dedupeKey))
    }
}

/**
 * Publisher view — adapters take this dependency, not the whole bus, so unit
 * tests can feed a [TestEventPublisher] without touching the singleton.
 */
interface EventPublisher {
    fun publish(event: Event)
    fun publish(
        kind: EventKind,
        source: String,
        payload: Map<String, String> = emptyMap(),
        actor: String? = null,
        confidence: Float = 1f,
        sensitivity: Event.Sensitivity = Event.Sensitivity.PERSONAL,
        dedupeKey: String? = null,
    )
}
