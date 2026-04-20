package com.jarvis.assistant.core.events.adapters

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.core.events.EventAdapter
import com.jarvis.assistant.core.events.EventKind
import com.jarvis.assistant.core.events.EventPublisher

/**
 * ProximityEventAdapter — emits PROXIMITY_NEAR / PROXIMITY_FAR when the
 * phone's proximity sensor flips state (e.g. held to ear, placed face-
 * down on a desk, or cleared).
 *
 * Triggers can compose this with other streams — "held to ear while
 * driving" or "face-down during a meeting" — without the adapter
 * needing to know the use case. No permission required.
 */
class ProximityEventAdapter(
    private val context: Context,
) : EventAdapter {

    override val name: String = "ProximityEventAdapter"

    private var publisher: EventPublisher? = null
    private var listener: SensorEventListener? = null
    @Volatile private var lastNear: Boolean? = null

    override fun attach(publisher: EventPublisher) {
        if (listener != null) return
        this.publisher = publisher
        val sensorManager = context.getSystemService(SensorManager::class.java) ?: return
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) ?: run {
            Log.d(TAG, "no proximity sensor on this device")
            return
        }
        val max = sensor.maximumRange
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val v = event.values.firstOrNull() ?: return
                val near = v < max && v <= NEAR_THRESHOLD_CM
                if (lastNear == near) return
                lastNear = near
                emit(near)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }
        }
        sensorManager.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        listener = l
    }

    override fun detach() {
        val l = listener ?: return
        try {
            context.getSystemService(SensorManager::class.java)?.unregisterListener(l)
        } catch (_: Exception) { /* already gone */ }
        listener = null
        publisher = null
        lastNear = null
    }

    private fun emit(near: Boolean) {
        val kind = if (near) EventKind.PROXIMITY_NEAR else EventKind.PROXIMITY_FAR
        publisher?.publish(
            Event.of(
                kind = kind,
                source = "ProximityEventAdapter",
                sensitivity = Event.Sensitivity.PUBLIC,
            )
        )
    }

    companion object {
        private const val TAG = "ProximityEventAdapter"
        private const val NEAR_THRESHOLD_CM = 5f
    }
}
