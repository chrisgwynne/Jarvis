package com.jarvis.assistant.core.goals

/**
 * GoalType — the category of a longer-running user intent.
 *
 * A goal survives across ticks and typically spans multiple tools / plans
 * / proactive surfaces. It's the persistent cousin of
 * [com.jarvis.assistant.core.situations.Situation]: situations describe
 * "what's going on right now", goals describe "what Jarvis is helping the
 * user work toward".
 *
 * The enum is small on purpose. New types should only be added when at
 * least one trigger or tool is prepared to advance them.
 */
enum class GoalType {
    /** User is getting ready to leave (battery, keys-style reminders, etc). */
    GET_READY_TO_LEAVE,
    /** Preparing for an upcoming meeting / event. */
    PREPARE_FOR_MEETING,
    /** Returning or following up on a call the user missed. */
    RETURN_MISSED_CALL,
    /** Resolving a notification / task queue the user has pending. */
    CLEAR_NOTIFICATIONS,
    /** Wind-down / night-time routine (quiet hours, alarms, DND). */
    WIND_DOWN_FOR_NIGHT,
    /** Commute / travel-related sequence. */
    HANDLE_COMMUTE,
    /** Generic fallback for a plan with an identified user goal that
     *  doesn't match any of the specific types above. */
    AD_HOC,
}
