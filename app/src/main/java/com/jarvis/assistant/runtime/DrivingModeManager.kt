package com.jarvis.assistant.runtime

import android.app.UiModeManager
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * DrivingModeManager — detects car Bluetooth connections and manages
 * Jarvis's driving mode state.
 *
 * DRIVING MODE EFFECTS (flag only — JarvisRuntime reads [isDriving]):
 *   • JarvisRuntime should produce more concise TTS responses.
 *   • JarvisRuntime should auto-read incoming messages when driving=true.
 *
 * DETECTION SOURCES:
 *   1. Bluetooth ACL connect/disconnect — checks device BluetoothClass major
 *      for AUDIO_VIDEO (car stereo / hands-free head unit).
 *   2. UiModeManager car-mode changes (Android car dock / Android Auto).
 *   3. SCO audio state changes (hands-free profile connected).
 *
 * USAGE:
 *   val drivingManager = DrivingModeManager(context)
 *   drivingManager.onDrivingStateChanged = { driving -> ... }
 *   drivingManager.start(context)   // in onStartCommand / onCreate
 *   drivingManager.stop(context)    // in onDestroy
 *
 * PERMISSIONS REQUIRED (declare in manifest):
 *   android.permission.BLUETOOTH
 *   android.permission.BLUETOOTH_CONNECT   (Android 12+)
 *   android.permission.CHANGE_CONFIGURATION (for UiModeManager.enableCarMode)
 */
class DrivingModeManager(private val context: Context) {

    companion object {
        private const val TAG = "DrivingModeManager"
    }

    /** True when Jarvis considers us to be in a car. Thread-safe. */
    @Volatile var isDriving: Boolean = false
        private set

    /**
     * Optional callback invoked on the thread that triggered the state change
     * whenever [isDriving] flips.  Register before calling [start].
     */
    var onDrivingStateChanged: ((Boolean) -> Unit)? = null

    private val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager

    // Track which BT device triggered driving mode so we only exit on the same device
    @Volatile private var triggeringDevice: String? = null

    // ── BroadcastReceiver ─────────────────────────────────────────────────────

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED    -> handleBtConnected(intent)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> handleBtDisconnected(intent)
                AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED -> handleScoChange(intent)
                UiModeManager.ACTION_ENTER_CAR_MODE     -> enterDrivingMode(source = "UiMode-car-dock")
                UiModeManager.ACTION_EXIT_CAR_MODE      -> exitDrivingMode(source = "UiMode-car-dock")
            }
        }
    }

    private val intentFilter = IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        addAction(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED)
        addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
        addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Register the broadcast receiver and begin listening for driving signals.
     * Call from Service.onCreate() or Service.onStartCommand().
     */
    fun start(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, intentFilter)
            }
            Log.d(TAG, "DrivingModeManager started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register driving receiver: ${e.message}", e)
        }
    }

    /**
     * Unregister the receiver and clean up.
     * Call from Service.onDestroy().
     */
    fun stop(context: Context) {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Receiver not registered — safe to ignore
        }
        // Ensure car mode is disabled when service stops
        if (isDriving) {
            disableCarMode()
        }
        Log.d(TAG, "DrivingModeManager stopped")
    }

    // ── Bluetooth handlers ────────────────────────────────────────────────────

    private fun handleBtConnected(intent: Intent) {
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        if (device == null) return

        // Check BLUETOOTH_CONNECT at runtime (required on API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "BLUETOOTH_CONNECT not granted — cannot inspect device class")
                return
            }
        }

        val deviceClass = device.bluetoothClass ?: return
        val majorClass  = deviceClass.majorDeviceClass

        // Only trigger driving mode for car-specific device classes.
        // AUDIO_VIDEO major class is too broad — it includes headphones, earbuds,
        // and speakers.  Check the full device class against car-only subtypes.
        val isCarDevice = deviceClass.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
                          deviceClass.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE

        if (isCarDevice) {
            Log.d(TAG, "Car Bluetooth connected: ${device.address}, class=${deviceClass.deviceClass}")
            triggeringDevice = device.address
            enterDrivingMode(source = "bluetooth-car")
        } else {
            Log.v(TAG, "BT device connected (class=${deviceClass.deviceClass}) — not a car device, ignoring")
        }
    }

    private fun handleBtDisconnected(intent: Intent) {
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        // Only exit driving mode if the same device that triggered it disconnects
        if (device != null && device.address == triggeringDevice) {
            Log.d(TAG, "Triggering car BT device disconnected: ${device.address}")
            triggeringDevice = null
            exitDrivingMode(source = "bluetooth-disconnect")
        }
    }

    private fun handleScoChange(intent: Intent) {
        // SCO audio state alone is not a reliable driving signal — it fires whenever
        // any Bluetooth headset (earbuds, walking headphones, etc.) connects.
        // Driving mode is only entered via confirmed car BT device class or car-dock UiMode.
        val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
        Log.v(TAG, "SCO state changed: $state — not used for driving detection")
    }

    // ── State transitions ─────────────────────────────────────────────────────

    private fun enterDrivingMode(source: String) {
        if (isDriving) return  // Already driving
        isDriving = true
        Log.i(TAG, "Driving mode ON (source=$source)")
        enableCarMode()
        onDrivingStateChanged?.invoke(true)
    }

    private fun exitDrivingMode(source: String) {
        if (!isDriving) return  // Already stopped
        isDriving = false
        Log.i(TAG, "Driving mode OFF (source=$source)")
        disableCarMode()
        onDrivingStateChanged?.invoke(false)
    }

    // ── UiModeManager car-mode helpers ────────────────────────────────────────

    /**
     * Tells Android we are in car mode.
     * Requires CHANGE_CONFIGURATION permission (normal permission, auto-granted).
     */
    private fun enableCarMode() {
        try {
            uiModeManager.enableCarMode(0)
            Log.d(TAG, "UiModeManager.enableCarMode() called")
        } catch (e: Exception) {
            Log.w(TAG, "enableCarMode() failed: ${e.message}")
        }
    }

    /**
     * Tells Android we have left car mode.
     */
    private fun disableCarMode() {
        try {
            uiModeManager.disableCarMode(0)
            Log.d(TAG, "UiModeManager.disableCarMode() called")
        } catch (e: Exception) {
            Log.w(TAG, "disableCarMode() failed: ${e.message}")
        }
    }
}
