package com.jarvis.assistant.brain

/**
 * BrainEventType — all observable events the brain can log and reason about.
 *
 * Each value maps to one or more Android system events or runtime hooks.
 * The string name is stored in [BrainEvent.type] so enum renames require a
 * migration — add values freely but never rename existing ones.
 */
enum class BrainEventType {

    // ── Device screen ──────────────────────────────────────────────────────────
    SCREEN_ON,
    SCREEN_OFF,

    // ── Power ─────────────────────────────────────────────────────────────────
    CHARGER_CONNECTED,
    CHARGER_DISCONNECTED,
    BATTERY_LOW,               // fired once per session when battery drops below 20 %

    // ── Connectivity ──────────────────────────────────────────────────────────
    BLUETOOTH_CONNECTED,       // [BrainEvent.bluetoothDevice] = device name
    BLUETOOTH_DISCONNECTED,
    HEADPHONES_CONNECTED,
    HEADPHONES_DISCONNECTED,

    // ── Location ──────────────────────────────────────────────────────────────
    LOCATION_HOME,
    LOCATION_AWAY,

    // ── User interactions with Jarvis ──────────────────────────────────────────
    USER_MESSAGE,
    JARVIS_RESPONSE,

    // ── Media ─────────────────────────────────────────────────────────────────
    MEDIA_PLAY_START,
    MEDIA_PLAY_STOP,

    // ── Apps ──────────────────────────────────────────────────────────────────
    APP_OPEN,                  // [BrainEvent.packageName] = app package
    APP_CLOSE,

    // ── Alarms / timers ───────────────────────────────────────────────────────
    ALARM_SET,
    TIMER_SET,
}
