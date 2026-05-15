package com.jarvis.assistant.ambient

/**
 * Every discrete ambient signal the system can observe.
 *
 * These map 1-to-1 to raw observations (not to proactive decisions — those
 * are [com.jarvis.assistant.proactive.ProactiveEventType]).
 */
enum class AmbientEventType {

    // ── Location ──────────────────────────────────────────────────────────────
    ARRIVED_HOME,
    LEFT_HOME,
    ARRIVED_SHOP,
    ARRIVED_WORK,
    ARRIVED_KNOWN_PLACE,

    // ── App activity ─────────────────────────────────────────────────────────
    /** A tracked app came to the foreground. */
    APP_OPENED,

    // ── Connectivity ─────────────────────────────────────────────────────────
    /** A known car Bluetooth device connected. */
    CONNECTED_CAR_BLUETOOTH,
    DISCONNECTED_CAR_BLUETOOTH,

    // ── Calendar / tasks ─────────────────────────────────────────────────────
    /** A calendar event with a known routine departure pattern is upcoming. */
    CALENDAR_EVENT_SOON,
    /** One or more Todoist tasks are overdue. */
    TODOIST_OVERDUE,
    /** A Todoist task matches the user's current location (shop / work). */
    TODOIST_ITEM_NEAR_LOCATION,

    // ── Home Assistant ────────────────────────────────────────────────────────
    /** An HA device (printer, oven, workshop) is running while the user is away. */
    HA_DEVICE_RUNNING_AWAY,
    /** An HA motion/binary sensor fired. */
    HA_MOTION_DETECTED,

    // ── Device / phone ────────────────────────────────────────────────────────
    PHONE_CHARGING,
    PHONE_UNPLUGGED,
    SCREEN_ON,

    // ── Messaging ─────────────────────────────────────────────────────────────
    /** An unread customer or work message arrived. */
    CUSTOMER_MESSAGE_UNREAD,
}
