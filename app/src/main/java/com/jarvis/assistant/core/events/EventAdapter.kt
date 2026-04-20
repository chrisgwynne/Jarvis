package com.jarvis.assistant.core.events

/**
 * EventAdapter — contract for a component that observes a sensed signal
 * source (notification listener, telephony callback, broadcast receiver,
 * callback API) and emits [Event]s to the bus.
 *
 * Adapters own their lifecycle. [attach] registers whatever OS hooks they
 * need; [detach] unregisters them. Implementations must be idempotent so
 * the runtime can call them safely on service restarts.
 */
interface EventAdapter {
    val name: String
    fun attach(publisher: EventPublisher)
    fun detach()
}
