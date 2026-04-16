package com.jarvis.assistant.context

/** Immutable snapshot of all device context at one point in time. */
data class DeviceContext(
    val timestamp: Long = System.currentTimeMillis(),
    val date: String,
    val time: String,
    val timezone: String,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val deviceModel: String,
    val isOnline: Boolean,
    val audioRoute: AudioRoute,
    val headsetConnected: Boolean,
    /** Approximate location string, e.g. "London, United Kingdom". Null if permission not granted. */
    val location: String? = null
)

enum class AudioRoute {
    SPEAKER,
    EARPIECE,
    WIRED_HEADSET,
    BLUETOOTH_HEADSET,
    UNKNOWN
}
