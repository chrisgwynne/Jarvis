package com.jarvis.assistant.brain

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.util.Log
import com.jarvis.assistant.brain.db.dao.BrainEventDao
import com.jarvis.assistant.brain.db.entity.BrainEvent
import com.jarvis.assistant.core.store.DeviceStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * BrainEventCollector — the OBSERVE layer.
 *
 * Registers a dynamic [BroadcastReceiver] for system events (screen, charger,
 * Bluetooth, headphones) and exposes hook methods that [JarvisRuntime] calls
 * for message/media events.
 *
 * All writes are fire-and-forget on [Dispatchers.IO] — the caller is never blocked.
 *
 * Call [register] when the service starts, [unregister] when it stops.
 */
class BrainEventCollector(
    private val context: Context,
    private val dao: BrainEventDao
) {
    companion object {
        private const val TAG = "BrainEventCollector"
        private const val PREF_NAME = "jarvis_brain_collector"
        private const val KEY_LOCATION = "location_state"
        private const val KEY_BATTERY_LOW_FIRED = "battery_low_fired"
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ── Dynamic BroadcastReceiver ──────────────────────────────────────────────

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON              -> log(BrainEventType.SCREEN_ON)
                Intent.ACTION_SCREEN_OFF             -> log(BrainEventType.SCREEN_OFF)
                Intent.ACTION_POWER_CONNECTED        -> {
                    prefs.edit().remove(KEY_BATTERY_LOW_FIRED).apply()
                    log(BrainEventType.CHARGER_CONNECTED)
                }
                Intent.ACTION_POWER_DISCONNECTED     -> log(BrainEventType.CHARGER_DISCONNECTED)
                Intent.ACTION_BATTERY_CHANGED        -> handleBatteryChanged(intent)
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    log(BrainEventType.BLUETOOTH_CONNECTED, bluetoothDevice = deviceName(device))
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    log(BrainEventType.BLUETOOTH_DISCONNECTED, bluetoothDevice = deviceName(device))
                }
                AudioManager.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", -1)
                    if (state == 1) log(BrainEventType.HEADPHONES_CONNECTED)
                    else if (state == 0) log(BrainEventType.HEADPHONES_DISCONNECTED)
                }
            }
        }
    }

    private fun handleBatteryChanged(intent: Intent) {
        val level   = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale   = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct     = if (scale > 0) (level * 100 / scale) else level
        val fired   = prefs.getBoolean(KEY_BATTERY_LOW_FIRED, false)
        if (pct in 1..20 && !fired) {
            prefs.edit().putBoolean(KEY_BATTERY_LOW_FIRED, true).apply()
            log(BrainEventType.BATTERY_LOW)
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(AudioManager.ACTION_HEADSET_PLUG)
        }
        context.registerReceiver(receiver, filter)
        Log.d(TAG, "Collector registered")
    }

    fun unregister() {
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        Log.d(TAG, "Collector unregistered")
    }

    // ── Hook methods called from JarvisRuntime ─────────────────────────────────

    fun onUserMessage(text: String) = log(BrainEventType.USER_MESSAGE, extra = "len=${text.length}")
    fun onJarvisResponse(text: String) = log(BrainEventType.JARVIS_RESPONSE, extra = "len=${text.length}")
    fun onMediaPlay(appPackage: String? = null) = log(BrainEventType.MEDIA_PLAY_START, packageName = appPackage)
    fun onMediaStop() = log(BrainEventType.MEDIA_PLAY_STOP)
    fun onAppOpen(packageName: String) = log(BrainEventType.APP_OPEN, packageName = packageName)
    fun onAlarmSet() = log(BrainEventType.ALARM_SET)
    fun onTimerSet() = log(BrainEventType.TIMER_SET)

    /** Update the persisted location state ("home" / "away" / "unknown"). */
    fun updateLocationState(state: String) {
        val prev = prefs.getString(KEY_LOCATION, "unknown")
        prefs.edit().putString(KEY_LOCATION, state).apply()
        if (prev != state) {
            when (state) {
                "home" -> log(BrainEventType.LOCATION_HOME)
                "away" -> log(BrainEventType.LOCATION_AWAY)
            }
        }
    }

    // ── Core log function ─────────────────────────────────────────────────────

    fun log(
        type: BrainEventType,
        bluetoothDevice: String? = null,
        packageName: String? = null,
        extra: String? = null
    ) {
        val now   = System.currentTimeMillis()
        val local = LocalDateTime.now()
        val dow   = local.dayOfWeek
        val state = DeviceStateStore.current

        val event = BrainEvent(
            type           = type.name,
            timestamp      = now,
            hourOfDay      = local.hour,
            minuteOfHour   = local.minute,
            dayOfWeek      = dow.value,           // 1=Mon … 7=Sun
            isWeekend      = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY,
            locationState  = prefs.getString(KEY_LOCATION, "unknown") ?: "unknown",
            batteryPct     = state.batteryPercent,
            isCharging     = state.isCharging,
            screenOn       = state.runtimeState.toString() != "Idle",
            bluetoothDevice = bluetoothDevice,
            packageName    = packageName,
            extra          = extra
        )

        scope.launch {
            try {
                dao.insert(event)
                Log.v(TAG, "Logged: ${type.name}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to log event ${type.name}: ${e.message}")
            }
        }
    }

    private fun deviceName(device: BluetoothDevice?): String? =
        try { device?.name } catch (_: SecurityException) { null }
}
