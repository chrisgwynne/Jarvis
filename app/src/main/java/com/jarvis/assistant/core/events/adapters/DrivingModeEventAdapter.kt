package com.jarvis.assistant.core.events.adapters

import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.core.events.EventAdapter
import com.jarvis.assistant.core.events.EventKind
import com.jarvis.assistant.core.events.EventPublisher
import com.jarvis.assistant.runtime.DrivingModeManager

/**
 * DrivingModeEventAdapter — chains onto [DrivingModeManager.onDrivingStateChanged]
 * and emits DRIVING_MODE_ON/OFF events when the flag flips.
 *
 * Preserves any existing callback so the JarvisRuntime's TTS announcement
 * keeps working. [detach] restores the original callback.
 */
class DrivingModeEventAdapter(
    private val manager: DrivingModeManager,
) : EventAdapter {

    override val name: String = "DrivingModeEventAdapter"

    private var previous: ((Boolean) -> Unit)? = null
    private var chained: ((Boolean) -> Unit)? = null

    override fun attach(publisher: EventPublisher) {
        if (chained != null) return
        previous = manager.onDrivingStateChanged
        val prev = previous
        val wrapped: (Boolean) -> Unit = { driving ->
            prev?.invoke(driving)
            publisher.publish(
                Event.of(
                    kind = if (driving) EventKind.DRIVING_MODE_ON else EventKind.DRIVING_MODE_OFF,
                    source = "DrivingModeManager",
                    sensitivity = Event.Sensitivity.PUBLIC,
                )
            )
        }
        chained = wrapped
        manager.onDrivingStateChanged = wrapped
    }

    override fun detach() {
        if (chained != null && manager.onDrivingStateChanged === chained) {
            manager.onDrivingStateChanged = previous
        }
        chained = null
        previous = null
    }
}
