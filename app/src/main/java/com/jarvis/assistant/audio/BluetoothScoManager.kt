package com.jarvis.assistant.audio

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

// Needed for ACTION_CONNECTION_STATE_CHANGED
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED

/**
 * BluetoothScoManager — routes voice audio through a connected Bluetooth headset.
 *
 * WHY SCO?
 *   Bluetooth headsets use the SCO (Synchronous Connection-Oriented) audio
 *   channel for bidirectional narrowband audio — microphone AND speaker at the
 *   same time.  Without SCO, TTS plays on the phone speaker even when a headset
 *   is connected, and the microphone captures room audio instead of mouth audio.
 *
 * LIFECYCLE:
 *   call start()   once when JarvisRuntime starts
 *   call connect() before the first listen/speak cycle
 *   call disconnect() when returning to wake-word mode
 *   call release() when JarvisRuntime stops
 *
 * PERMISSIONS REQUIRED (already in AndroidManifest):
 *   android.permission.BLUETOOTH
 *   android.permission.BLUETOOTH_CONNECT  (Android 12+)
 *   android.permission.MODIFY_AUDIO_SETTINGS
 */
class BluetoothScoManager(private val context: Context) {

    companion object {
        private const val TAG          = "BluetoothScoManager"
        private const val SCO_TIMEOUT  = 4_000L   // ms to wait for SCO connection
        private const val POLL_DELAY   = 100L     // ms between state polls
    }

    private val audioManager     = context.getSystemService(AudioManager::class.java)!!
    private val bluetoothAdapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
    private var headsetProfile: BluetoothHeadset? = null
    private var scoActive = false
    private var scoReceiverRegistered = false
    private var headsetReceiverRegistered = false

    /**
     * Optional callback fired (on the main thread, via BroadcastReceiver) when a
     * Bluetooth headset connects or disconnects while the manager is running.
     * [connected] = true → headset just connected; false → disconnected.
     * Set before calling [start]; cleared automatically on [release].
     */
    var onHeadsetConnectionChanged: ((connected: Boolean) -> Unit)? = null

    // ── SCO state receiver ────────────────────────────────────────────────────

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
            val state = intent.getIntExtra(
                AudioManager.EXTRA_SCO_AUDIO_STATE,
                AudioManager.SCO_AUDIO_STATE_ERROR
            )
            scoActive = (state == AudioManager.SCO_AUDIO_STATE_CONNECTED)
            Log.d(TAG, "SCO state updated → active=$scoActive (raw=$state)")
        }
    }

    // ── Headset connection-state receiver ─────────────────────────────────────

    private val headsetConnectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            when (state) {
                STATE_CONNECTED -> {
                    Log.i(TAG, "Bluetooth headset connected")
                    onHeadsetConnectionChanged?.invoke(true)
                }
                STATE_DISCONNECTED -> {
                    Log.i(TAG, "Bluetooth headset disconnected — dropping SCO")
                    scoActive = false
                    onHeadsetConnectionChanged?.invoke(false)
                }
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** True if at least one Bluetooth headset device is connected. */
    val isHeadsetConnected: Boolean
        get() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Missing BLUETOOTH_CONNECT permission; cannot check headset status")
                return false
            }
            return try {
                headsetProfile?.connectedDevices?.isNotEmpty() == true
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException checking connected devices", e)
                false
            }
        }

    /** True if the SCO channel is currently up and routing audio. */
    val isScoActive: Boolean get() = scoActive

    /**
     * Register the SCO state receiver and acquire the Headset profile proxy.
     * Call once when the runtime service starts.
     */
    fun start() {
        if (!scoReceiverRegistered) {
            context.registerReceiver(
                scoReceiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            )
            scoReceiverRegistered = true
        }
        if (!headsetReceiverRegistered) {
            context.registerReceiver(
                headsetConnectionReceiver,
                IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            )
            headsetReceiverRegistered = true
        }

        bluetoothAdapter?.getProfileProxy(
            context,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        headsetProfile = proxy as BluetoothHeadset
                        val alreadyConnected = try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                Log.d(TAG, "Headset profile proxy acquired; no BLUETOOTH_CONNECT permission")
                                false
                            } else {
                                val count = headsetProfile?.connectedDevices?.size ?: 0
                                Log.d(TAG, "Headset profile proxy acquired; devices=$count")
                                count > 0
                            }
                        } catch (e: SecurityException) {
                            Log.e(TAG, "SecurityException checking connected devices", e)
                            false
                        }
                        // If a headset was already connected before the proxy was ready,
                        // fire the callback so the runtime can restart SCO-aware wake detection.
                        if (alreadyConnected) {
                            Log.i(TAG, "Headset already connected when proxy acquired — notifying runtime")
                            onHeadsetConnectionChanged?.invoke(true)
                        }
                    }
                }
                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HEADSET) {
                        headsetProfile = null
                        Log.d(TAG, "Headset profile proxy released")
                    }
                }
            },
            BluetoothProfile.HEADSET
        )
    }

    /**
     * Start the SCO connection and wait for it to become active (up to 4 s).
     * Returns true if SCO is now active, false if no headset or timed out.
     *
     * Safe to call even if no headset is connected — returns false immediately.
     */
    @Suppress("DEPRECATION")
    suspend fun connect(): Boolean {
        if (!isHeadsetConnected) {
            Log.d(TAG, "No headset connected — skipping SCO")
            return false
        }
        if (scoActive) {
            Log.d(TAG, "SCO already active")
            return true
        }

        Log.d(TAG, "Starting SCO…")
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true

        val connected = withTimeoutOrNull(SCO_TIMEOUT) {
            while (!scoActive) delay(POLL_DELAY)
            true
        } == true

        if (!connected) {
            // SCO negotiation timed out — the earphone may not support HFP/SCO
            // (e.g. A2DP-only mode) or the BT stack is busy. Roll back the
            // isBluetoothScoOn flag so subsequent AudioRecord / SpeechRecognizer
            // calls use the built-in mic rather than a non-existent SCO channel.
            Log.w(TAG, "SCO timed out — resetting audio routing to built-in mic")
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
        }

        Log.d(TAG, "SCO connect result: $connected")
        return connected
    }

    /**
     * Tear down the SCO channel and restore normal audio routing.
     * Safe to call even if SCO is not active.
     */
    @Suppress("DEPRECATION")
    fun disconnect() {
        if (!scoActive && !audioManager.isBluetoothScoOn) return
        Log.d(TAG, "Stopping SCO")
        audioManager.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false
        scoActive = false
    }

    /**
     * Full teardown — disconnect SCO, unregister receiver, release profile proxy.
     * Call when JarvisRuntime stops.
     */
    fun release() {
        disconnect()
        onHeadsetConnectionChanged = null
        if (scoReceiverRegistered) {
            try {
                context.unregisterReceiver(scoReceiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "scoReceiver was already unregistered: ${e.message}")
            }
            scoReceiverRegistered = false
        }
        if (headsetReceiverRegistered) {
            try {
                context.unregisterReceiver(headsetConnectionReceiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "headsetConnectionReceiver was already unregistered: ${e.message}")
            }
            headsetReceiverRegistered = false
        }
        headsetProfile?.let {
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, it)
        }
        headsetProfile = null
    }
}
