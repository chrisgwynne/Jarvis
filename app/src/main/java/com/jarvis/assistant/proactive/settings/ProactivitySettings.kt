package com.jarvis.assistant.proactive.settings

import com.jarvis.assistant.proactive.ProactiveEventType

/**
 * ProactivitySettings — user-visible policy that gates the ProactiveEngine.
 *
 * Three orthogonal levers, in priority order:
 *
 *   1. **Master switch.**  [enabled] = false hard-disables every proactive
 *      surface (speech AND notification AND passive UI hints).  Learning
 *      and trace recording continue silently because those don't surface
 *      to the user.
 *
 *   2. **Category filter.**  Each `*Enabled` flag corresponds to a family
 *      of triggers (suggestions, reminders, location alerts, …) — see
 *      [isCategoryEnabled] for the event-type → flag mapping.  A disabled
 *      category drops the event silently with reason `category_disabled`.
 *
 *   3. **Mode & quiet hours.**  [interruptionMode] dictates whether the
 *      engine speaks, notifies only, or stays silent; [quietHoursEnabled]
 *      with [quietStartMinute] / [quietEndMinute] suppress everything
 *      except urgent events (when [allowUrgentDuringQuietHours] = true).
 *
 * Times are stored as minutes-from-midnight (0..1439) so the data class is
 * Parcelable-friendly without depending on java.time / Android API levels.
 */
data class ProactivitySettings(
    val enabled: Boolean,
    val quietHoursEnabled: Boolean,
    /** Minutes-from-midnight (0..1439).  Default 22:00 = 1320. */
    val quietStartMinute: Int,
    /** Minutes-from-midnight (0..1439).  Default 07:00 = 420. */
    val quietEndMinute: Int,
    val allowUrgentDuringQuietHours: Boolean,
    val suggestionsEnabled: Boolean,
    val remindersEnabled: Boolean,
    val locationAlertsEnabled: Boolean,
    val homeAssistantAlertsEnabled: Boolean,
    val calendarNudgesEnabled: Boolean,
    val learningObservationsEnabled: Boolean,
    val safetySecurityAlertsEnabled: Boolean,
    val interruptionMode: InterruptionMode,
    val sensitivity: ProactivitySensitivity,
    val globalCooldownMinutes: Int,
) {
    companion object {
        /** Conservative defaults — opt-in for everything visible to the user. */
        val DEFAULT = ProactivitySettings(
            enabled                       = false,
            quietHoursEnabled             = true,
            quietStartMinute              = 22 * 60,
            quietEndMinute                = 7  * 60,
            allowUrgentDuringQuietHours   = true,
            suggestionsEnabled            = true,
            remindersEnabled              = true,
            locationAlertsEnabled         = true,
            homeAssistantAlertsEnabled    = false,
            calendarNudgesEnabled         = true,
            learningObservationsEnabled   = true,
            safetySecurityAlertsEnabled   = true,
            interruptionMode              = InterruptionMode.SPEAK_WHEN_ACTIVE,
            sensitivity                   = ProactivitySensitivity.MEDIUM,
            globalCooldownMinutes         = 30,
        )
    }

    /**
     * True when [nowMinute] (0..1439) falls inside [quietStartMinute,
     * quietEndMinute), wrapping past midnight.  Returns false when quiet
     * hours are disabled.
     */
    fun isQuietHourMinute(nowMinute: Int): Boolean {
        if (!quietHoursEnabled) return false
        val start = quietStartMinute
        val end   = quietEndMinute
        if (start == end) return false   // zero-length window
        return if (start < end) {
            nowMinute in start until end
        } else {
            // Overnight: e.g. 22:00 → 07:00.  In window when
            // now >= start OR now < end.
            nowMinute >= start || nowMinute < end
        }
    }

    /**
     * Map a [ProactiveEventType] to its category toggle.  Event types we
     * don't recognise default to the suggestions bucket so a new trigger
     * doesn't accidentally bypass the gate.
     */
    fun isCategoryEnabled(type: ProactiveEventType): Boolean = when (type) {
        ProactiveEventType.LOW_BATTERY          -> safetySecurityAlertsEnabled
        ProactiveEventType.UPCOMING_REMINDER    -> remindersEnabled
        ProactiveEventType.MISSED_CALL          -> safetySecurityAlertsEnabled
        ProactiveEventType.BEHAVIORAL_LEARNING  -> learningObservationsEnabled
        ProactiveEventType.UNREAD_NOTIFICATION  -> suggestionsEnabled
        ProactiveEventType.UPCOMING_MEETING     -> calendarNudgesEnabled
        ProactiveEventType.MEETING_STARTING_SOON-> calendarNudgesEnabled
        ProactiveEventType.DAILY_AGENDA         -> calendarNudgesEnabled
        ProactiveEventType.ARRIVED_HOME,
        ProactiveEventType.LEFT_HOME,
        ProactiveEventType.ARRIVED_KNOWN_PLACE  -> locationAlertsEnabled
        // Scheduled-reminder events ride the existing category toggles —
        // Calendar offsets on the calendar bucket, Todoist + Local on
        // the reminders bucket.  Per-source enables live on
        // [com.jarvis.assistant.proactive.scheduled.ScheduledReminderSettings].
        ProactiveEventType.CALENDAR_EVENT_30M,
        ProactiveEventType.CALENDAR_EVENT_10M   -> calendarNudgesEnabled
        ProactiveEventType.TODOIST_TASK_30M,
        ProactiveEventType.TODOIST_TASK_10M,
        ProactiveEventType.LOCAL_REMINDER_30M,
        ProactiveEventType.LOCAL_REMINDER_10M   -> remindersEnabled
    }

    /**
     * Event types that may break through quiet hours when
     * [allowUrgentDuringQuietHours] is true.  Hard-coded list — these are
     * the events where missing them creates real user harm.
     */
    fun isUrgentEvent(type: ProactiveEventType): Boolean = when (type) {
        ProactiveEventType.LOW_BATTERY,
        ProactiveEventType.MEETING_STARTING_SOON,
        ProactiveEventType.UPCOMING_REMINDER       -> true
        // 10-minute scheduled-reminder offsets are urgent enough to
        // break through quiet hours when the user allowed urgents — the
        // 30-minute counterparts are not.
        ProactiveEventType.CALENDAR_EVENT_10M,
        ProactiveEventType.TODOIST_TASK_10M,
        ProactiveEventType.LOCAL_REMINDER_10M      -> true
        else                                       -> false
    }
}

/**
 * How disruptive Jarvis is allowed to be when an event passes every other
 * gate.  Increasing order of intrusiveness.
 */
enum class InterruptionMode(val displayLabel: String, val description: String) {
    SILENT(
        "Silent",
        "Log only — no notification, no speech.",
    ),
    NOTIFY_ONLY(
        "Notify only",
        "Post a phone notification.  Never speak.",
    ),
    SPEAK_WHEN_ACTIVE(
        "Speak when active",
        "Speak only when you've recently interacted with Jarvis.",
    ),
    SPEAK_ANYTIME(
        "Speak anytime",
        "Speak whenever appropriate (still respects quiet hours).",
    ),
}

/**
 * Sensitivity slider — translates to a final-score threshold multiplier in
 * the engine.  Lower sensitivity = higher threshold = fewer events.
 */
enum class ProactivitySensitivity(
    val displayLabel: String,
    /**
     * Multiplier applied to the default activeThreshold / passiveThreshold
     * from ProactiveConfig.  >1 raises the bar (fewer events); <1 lowers
     * it (more events).  Calibrated empirically: at MEDIUM the engine
     * matches the previously hardcoded defaults.
     */
    val thresholdMultiplier: Float,
) {
    LOW(   "Low",    1.20f),
    MEDIUM("Medium", 1.00f),
    HIGH(  "High",   0.80f),
}
