package com.jarvis.assistant.proactive

/**
 * ProactiveEventType — the category of a proactive event.
 *
 * Each variant corresponds to one event generator inside [EventGenerator]
 * and one cooldown slot inside [CooldownStore].
 *
 * [cooldownKey] is the stable string used as the per-type cooldown key.
 * It is derived from the enum name so adding new types automatically gets
 * a unique key without manual maintenance.
 */
enum class ProactiveEventType {

    /** Device battery is at or below [ProactiveConfig.batteryLow] and not charging. */
    LOW_BATTERY,

    /** A scheduled reminder is within the [ProactiveConfig.reminderWindowMs] look-ahead. */
    UPCOMING_REMINDER,

    /** One or more cellular calls were missed since the last surfacing. */
    MISSED_CALL,

    /** Suggestions or insights derived from user behavior analysis. */
    BEHAVIORAL_LEARNING,

    /** One or more important notifications arrived while Jarvis was idle. */
    UNREAD_NOTIFICATION,

    /** A calendar meeting is coming up within the look-ahead window. */
    UPCOMING_MEETING,

    /** A calendar meeting is starting imminently (≤ meetingUrgentMs). */
    MEETING_STARTING_SOON,

    /** Morning-window one-shot summary of today's calendar. */
    DAILY_AGENDA,

    /** User arrived at their learned HOME place. */
    ARRIVED_HOME,

    /** User left their learned HOME place. */
    LEFT_HOME,

    /** User arrived at a recurring known (but non-home) place. */
    ARRIVED_KNOWN_PLACE;

    /**
     * Stable, lowercase key used to namespace this type inside [CooldownStore].
     *
     * Examples: `"low_battery"`, `"upcoming_reminder"`, `"missed_call"`.
     */
    val cooldownKey: String
        get() = name.lowercase()
}
