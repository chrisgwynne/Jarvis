package com.jarvis.assistant.core.events.adapters

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.core.events.EventAdapter
import com.jarvis.assistant.core.events.EventKind
import com.jarvis.assistant.core.events.EventPublisher

/**
 * BatteryEventAdapter — edge-only broadcaster for power plug and charger
 * events, plus a first-crossing LOW threshold. Intentionally does not
 * publish ACTION_BATTERY_CHANGED on every 1% drop — the ContextEngine
 * already polls for live level, and flooding the bus would be wasteful.
 */
class BatteryEventAdapter(
    private val context: Context,
    private val lowThreshold: Int = 15,
) : EventAdapter {

    override val name: String = "BatteryEventAdapter"

    private var publisher: EventPublisher? = null
    private var receiver: BroadcastReceiver? = null
    private var lowEmittedAt: Long = 0L

    override fun attach(publisher: EventPublisher) {
        if (receiver != null) return
        this.publisher = publisher
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_POWER_CONNECTED -> emit(EventKind.POWER_CONNECTED, intent)
                    Intent.ACTION_POWER_DISCONNECTED -> emit(EventKind.POWER_DISCONNECTED, intent)
                    Intent.ACTION_BATTERY_CHANGED -> maybeEmitLow(intent)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(rcv, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(rcv, filter)
            }
            receiver = rcv
        } catch (e: Exception) {
            Log.w(TAG, "registerReceiver failed: ${e.message}")
        }
    }

    override fun detach() {
        val rcv = receiver ?: return
        try {
            context.unregisterReceiver(rcv)
        } catch (_: Exception) { /* already unregistered */ }
        receiver = null
        publisher = null
    }

    private fun emit(kind: EventKind, intent: Intent) {
        publisher?.publish(
            Event.of(
                kind = kind,
                source = "BatteryEventAdapter",
                payload = mapOf("battery_pct" to readPct(intent).toString()),
                sensitivity = Event.Sensitivity.PUBLIC,
            )
        )
    }

    private fun maybeEmitLow(intent: Intent) {
        val pct = readPct(intent)
        if (pct < 0 || pct > lowThreshold) return
        val now = System.currentTimeMillis()
        if (now - lowEmittedAt < LOW_COOLDOWN_MS) return
        lowEmittedAt = now
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        if (charging) return
        publisher?.publish(
            Event.of(
                kind = EventKind.BATTERY_LOW,
                source = "BatteryEventAdapter",
                payload = mapOf("battery_pct" to pct.toString()),
                sensitivity = Event.Sensitivity.PUBLIC,
                dedupeKey = "battery_low_${pct / 5 * 5}",
            )
        )
    }

    private fun readPct(intent: Intent): Int {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return -1
        return (level * 100f / scale).toInt()
    }

    companion object {
        private const val TAG = "BatteryEventAdapter"
        private const val LOW_COOLDOWN_MS = 5 * 60 * 1000L
    }
}
