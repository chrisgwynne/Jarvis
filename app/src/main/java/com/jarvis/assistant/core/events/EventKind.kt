package com.jarvis.assistant.core.events

/**
 * Canonical taxonomy of raw sensed signals that flow on [EventBus].
 *
 * A single kind corresponds to one class of observation, not to one proactive
 * trigger. Multiple triggers may compose the same kind. Keep values stable —
 * they are persisted in telemetry traces and used as dedupe-key prefixes.
 */
enum class EventKind {
    NOTIFICATION_POSTED,
    NOTIFICATION_REMOVED,
    CALL_RINGING,
    CALL_ANSWERED,
    CALL_ENDED,
    CALL_MISSED,
    SMS_RECEIVED,
    BATTERY_CHANGED,
    BATTERY_LOW,
    POWER_CONNECTED,
    POWER_DISCONNECTED,
    SCREEN_ON,
    SCREEN_OFF,
    HEADSET_CONNECTED,
    HEADSET_DISCONNECTED,
    BLUETOOTH_DEVICE_CONNECTED,
    BLUETOOTH_DEVICE_DISCONNECTED,
    NETWORK_AVAILABLE,
    NETWORK_LOST,
    WIFI_SSID_CHANGED,
    LOCATION_UPDATED,
    PLACE_ARRIVED,
    PLACE_LEFT,
    GEOFENCE_ENTERED,
    GEOFENCE_EXITED,
    CALENDAR_REFRESHED,
    MEETING_IMMINENT,
    DRIVING_MODE_ON,
    DRIVING_MODE_OFF,
    FOREGROUND_APP_CHANGED,
    SMART_HOME_STATE,
    BRAIN_PREDICTION,
    USER_UTTERANCE,
    JARVIS_SPEAKING_STARTED,
    JARVIS_SPEAKING_ENDED,
    TOOL_EXECUTED,
    PROACTIVE_DISPATCHED,
    USER_VERDICT,
}
