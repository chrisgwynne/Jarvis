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
    BEHAVIORAL_LEARNING;

    /**
     * Stable, lowercase key used to namespace this type inside [CooldownStore].
     *
     * Examples: `"low_battery"`, `"upcoming_reminder"`, `"missed_call"`.
     */
    val cooldownKey: String
        get() = name.lowercase()
}
