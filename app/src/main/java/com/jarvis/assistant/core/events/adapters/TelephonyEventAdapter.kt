package com.jarvis.assistant.core.events.adapters

import com.jarvis.assistant.call.CallEvent
import com.jarvis.assistant.call.integration.TelephonyCallMonitor
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.core.events.EventAdapter
import com.jarvis.assistant.core.events.EventKind
import com.jarvis.assistant.core.events.EventPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * TelephonyEventAdapter — subscribes to [TelephonyCallMonitor.events] and
 * republishes them as normalized [Event]s on the bus.
 *
 * Does not take ownership of the monitor; [TelephonyCallMonitor.start] is
 * already called by [com.jarvis.assistant.runtime.JarvisRuntime].
 */
class TelephonyEventAdapter(
    private val monitor: TelephonyCallMonitor,
    private val scope: CoroutineScope,
) : EventAdapter {

    override val name: String = "TelephonyEventAdapter"

    private var job: Job? = null

    override fun attach(publisher: EventPublisher) {
        if (job != null) return
        job = scope.launch {
            monitor.events.collect { e -> publish(publisher, e) }
        }
    }

    override fun detach() {
        job?.cancel()
        job = null
    }

    private fun publish(publisher: EventPublisher, e: CallEvent) {
        val (kind, info) = when (e) {
            is CallEvent.IncomingRinging -> EventKind.CALL_RINGING to e.callInfo
            is CallEvent.CallAnswered -> EventKind.CALL_ANSWERED to e.callInfo
            is CallEvent.OutgoingCallStarted -> EventKind.CALL_ANSWERED to e.callInfo
            is CallEvent.OutgoingCallEnded -> EventKind.CALL_ENDED to e.callInfo
            is CallEvent.CallEnded -> EventKind.CALL_ENDED to e.callInfo
            is CallEvent.CallStateChanged -> return
        }
        publisher.publish(
            Event.of(
                kind = kind,
                source = "TelephonyCallMonitor",
                payload = buildMap {
                    info.incomingNumber?.let { put("phone_number", it) }
                    if (info.resolvedDisplayName.isNotBlank()) put("contact_name", info.resolvedDisplayName)
                    put("source_type", info.sourceType.name)
                    put("is_known_contact", info.isKnownContact.toString())
                },
                sensitivity = Event.Sensitivity.PERSONAL,
            )
        )
    }
}
