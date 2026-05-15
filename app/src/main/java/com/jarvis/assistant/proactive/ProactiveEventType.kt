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
    ARRIVED_KNOWN_PLACE,

    // ── Scheduled reminders (Calendar / Todoist / local) ──────────────────
    // Each upstream item produces two events: one at 30 minutes before
    // and one at 10 minutes before, allowing the engine to score and
    // suppress them independently (e.g. the user may want only the 10m
    // nudge during quiet hours).  Source is encoded in the type so the
    // user can disable per-source via category toggles.
    /** Google / system Calendar event scheduled to start in ~30 minutes. */
    CALENDAR_EVENT_30M,
    /** Google / system Calendar event scheduled to start in ~10 minutes. */
    CALENDAR_EVENT_10M,
    /** Todoist task due in ~30 minutes. */
    TODOIST_TASK_30M,
    /** Todoist task due in ~10 minutes. */
    TODOIST_TASK_10M,
    /** Local (Jarvis) reminder due in ~30 minutes. */
    LOCAL_REMINDER_30M,
    /** Local (Jarvis) reminder due in ~10 minutes. */
    LOCAL_REMINDER_10M,

    // ── Ambient Intelligence ──────────────────────────────────────────────────
    /** Ambient routine suggestion (e.g. "you normally leave for football now"). */
    AMBIENT_ROUTINE_SUGGESTION,
    /** User is near a shop with matching Todoist items. */
    AMBIENT_LOCATION_TODOIST_MATCH,
    /** App-context nudge (e.g. "you opened Etsy — you have messages"). */
    AMBIENT_APP_CONTEXT_NUDGE,
    /** HA device running while nobody home. */
    AMBIENT_HOME_ASSISTANT_ALERT,
    /** Travel suggestion after car BT connects. */
    AMBIENT_TRAVEL_SUGGESTION,
    /** A learned routine was missed (e.g. usual departure time passed). */
    AMBIENT_MISSED_ROUTINE,
    /** Customer or work message nudge. */
    AMBIENT_CUSTOMER_MESSAGE_NUDGE;

    /**
     * Stable, lowercase key used to namespace this type inside [CooldownStore].
     *
     * Examples: `"low_battery"`, `"upcoming_reminder"`, `"missed_call"`.
     */
    val cooldownKey: String
        get() = name.lowercase()

    /**
     * Canonical action-class label recorded in [com.jarvis.assistant.core
     * .decisions.ActionLedger]. Multiple event types can share a class so
     * a voice command in the same domain suppresses a proactive nudge.
     */
    fun actionClassKey(): String = when (this) {
        LOW_BATTERY -> "BATTERY"
        UPCOMING_REMINDER -> "REMINDER"
        MISSED_CALL -> "CALL"
        BEHAVIORAL_LEARNING -> "BRAIN"
        UNREAD_NOTIFICATION -> "NOTIFICATION"
        UPCOMING_MEETING,
        MEETING_STARTING_SOON,
        DAILY_AGENDA -> "CALENDAR"
        ARRIVED_HOME,
        LEFT_HOME,
        ARRIVED_KNOWN_PLACE -> "LOCATION"
        CALENDAR_EVENT_30M,
        CALENDAR_EVENT_10M -> "CALENDAR"
        TODOIST_TASK_30M,
        TODOIST_TASK_10M -> "TODOIST"
        LOCAL_REMINDER_30M,
        LOCAL_REMINDER_10M -> "REMINDER"
        AMBIENT_ROUTINE_SUGGESTION,
        AMBIENT_MISSED_ROUTINE -> "AMBIENT_ROUTINE"
        AMBIENT_LOCATION_TODOIST_MATCH -> "AMBIENT_LOCATION"
        AMBIENT_APP_CONTEXT_NUDGE,
        AMBIENT_CUSTOMER_MESSAGE_NUDGE -> "AMBIENT_APP"
        AMBIENT_HOME_ASSISTANT_ALERT -> "AMBIENT_HA"
        AMBIENT_TRAVEL_SUGGESTION -> "AMBIENT_TRAVEL"
    }
}
