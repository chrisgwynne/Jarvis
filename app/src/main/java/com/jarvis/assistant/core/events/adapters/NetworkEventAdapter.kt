package com.jarvis.assistant.core.events.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.core.events.EventAdapter
import com.jarvis.assistant.core.events.EventKind
import com.jarvis.assistant.core.events.EventPublisher

/**
 * NetworkEventAdapter — real-time network availability + Wi-Fi SSID tracking
 * via [ConnectivityManager.NetworkCallback]. Replaces the previous polled
 * `isOnline()` model with edge events, letting triggers reason about
 * "arrived at work Wi-Fi" or "offline now" without a ticking loop.
 *
 * SSID inspection requires fine-location permission on Android 8.1+; if the
 * permission is not granted the adapter still emits NETWORK_AVAILABLE /
 * NETWORK_LOST events, it just omits the `ssid` payload field.
 */
class NetworkEventAdapter(
    private val context: Context,
) : EventAdapter {

    override val name: String = "NetworkEventAdapter"

    private var publisher: EventPublisher? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastSsid: String? = null

    override fun attach(publisher: EventPublisher) {
        if (callback != null) return
        this.publisher = publisher
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                publisher.publish(
                    Event.of(
                        kind = EventKind.NETWORK_AVAILABLE,
                        source = "NetworkEventAdapter",
                        sensitivity = Event.Sensitivity.PUBLIC,
                    )
                )
            }
            override fun onLost(network: Network) {
                val ssid = lastSsid
                lastSsid = null
                publisher.publish(
                    Event.of(
                        kind = EventKind.NETWORK_LOST,
                        source = "NetworkEventAdapter",
                        payload = if (ssid != null) mapOf("previous_ssid" to ssid) else emptyMap(),
                        sensitivity = Event.Sensitivity.PUBLIC,
                    )
                )
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
                val ssid = readSsid()
                if (ssid != null && ssid != lastSsid) {
                    lastSsid = ssid
                    publisher.publish(
                        Event.of(
                            kind = EventKind.WIFI_SSID_CHANGED,
                            source = "NetworkEventAdapter",
                            payload = mapOf("ssid" to ssid),
                            sensitivity = Event.Sensitivity.PERSONAL,
                            dedupeKey = "ssid_$ssid",
                        )
                    )
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, cb)
            callback = cb
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback failed: ${e.message}")
        }
    }

    override fun detach() {
        val cb = callback ?: return
        try {
            context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        } catch (_: Exception) { /* already unregistered */ }
        callback = null
        publisher = null
    }

    @SuppressLint("MissingPermission")
    private fun readSsid(): String? {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        return try {
            val info = wm.connectionInfo ?: return null
            val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.ssid else info.ssid
            val clean = raw?.trim('"')
            if (clean.isNullOrBlank() || clean == "<unknown ssid>") null else clean
        } catch (_: SecurityException) {
            null
        }
    }

    companion object { private const val TAG = "NetworkEventAdapter" }
}
