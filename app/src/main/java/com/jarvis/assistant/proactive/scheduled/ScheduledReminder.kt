package com.jarvis.assistant.proactive.scheduled

/**
 * Source of a [ScheduledReminderItem] — drives event-type selection in
 * the ProactiveEngine (CALENDAR_EVENT_30M vs TODOIST_TASK_10M etc.) and
 * the spoken-string template chosen by [ScheduledReminderPhraseBuilder].
 */
enum class ScheduledReminderSource { CALENDAR, TODOIST, LOCAL }

/**
 * A single upcoming item known to Jarvis at refresh time.  Sources
 * (Calendar / Todoist / local reminders) normalise their domain objects
 * into this shape so the engine can treat them uniformly.
 *
 * Items are immutable + value-typed so the diff between refresh cycles
 * is "stable id + start ms + title".  A change in any of those triggers
 * a reschedule of the corresponding [ScheduledReminderInstance]s.
 *
 * @param sourceId  Stable identifier from the source system.  For
 *                  Calendar this is the CalendarContract event id; for
 *                  Todoist it's the task id; for local reminders it's
 *                  the ScheduledItem row id.  Used as the dedupe key
 *                  base together with the offset.
 * @param startMs   Wall-clock milliseconds of the item's start time.
 * @param location  Optional location string — shown in the spoken text
 *                  for calendar events when present.
 */
data class ScheduledReminderItem(
    val source: ScheduledReminderSource,
    val sourceId: String,
    val title: String,
    val startMs: Long,
    val location: String? = null,
    /**
     * Optional fingerprint used by the diff logic: when [startMs] changes
     * but the source id doesn't, the engine cancels the old instances and
     * creates new ones for the updated time.  Defaults to startMs + title
     * — sources can override if they need finer granularity.
     */
    val fingerprint: String = "$startMs:$title",
)

/**
 * One scheduled "I will speak about this item at this time" intent.
 * The engine produces these from [ScheduledReminderItem]s using the
 * configured offset list (default: 30 + 10 minutes before).
 *
 * Instances are pure data — they're stored in
 * [ScheduledReminderInstanceStore] and consulted on every refresh to
 * dedupe.
 *
 * @param scheduledAtMs The wall-clock ms at which this instance should
 *                      fire — `item.startMs - offsetMinutes*60_000`.
 * @param itemTimeMs    The original item start time, retained for the
 *                      spoken-phrase template ("at 3" vs "in 30
 *                      minutes").
 * @param offsetMinutes The offset that produced this instance.  30 / 10
 *                      in production, but parameterised so the user can
 *                      tune later.
 * @param fired         True once the engine has emitted the matching
 *                      ProactiveEvent for this instance.
 * @param dismissed     True when the user cancelled the item upstream
 *                      (task completed / event deleted) so the engine
 *                      should not fire it even if the wall clock has
 *                      crossed [scheduledAtMs].
 * @param createdAtMs   Wall-clock ms at which the instance was created
 *                      — useful for diagnostics + late-firing logic.
 */
data class ScheduledReminderInstance(
    val source: ScheduledReminderSource,
    val sourceId: String,
    val title: String,
    val scheduledAtMs: Long,
    val itemTimeMs: Long,
    val offsetMinutes: Int,
    val fingerprint: String,
    val location: String? = null,
    val fired: Boolean = false,
    val dismissed: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    /**
     * Stable dedupe key used by [ScheduledReminderInstanceStore] AND
     * passed through into [com.jarvis.assistant.proactive.ProactiveEvent]
     * so the existing cooldown / dedupe stores see this as a single
     * conceptual surface.
     */
    val dedupeKey: String
        get() = "sched:${source.name.lowercase()}:$sourceId:$offsetMinutes"
}

/**
 * User-visible policy for the scheduled-reminder engine.  Read off
 * [com.jarvis.assistant.util.SettingsStore] on each refresh tick so
 * toggle changes take effect immediately.
 */
data class ScheduledReminderSettings(
    val calendarEnabled: Boolean,
    val todoistEnabled: Boolean,
    val localEnabled: Boolean,
    /** Speak 30-minute-before reminders. */
    val offset30mEnabled: Boolean,
    /** Speak 10-minute-before reminders. */
    val offset10mEnabled: Boolean,
    /** Post a notification when speech is suppressed. */
    val notifyFallbackEnabled: Boolean,
    /** Allow speaking proactive reminders even when Jarvis is idle
     *  (no recent voice interaction).  Off by default to avoid speaking
     *  into an empty room. */
    val backgroundSpeechEnabled: Boolean,
) {
    /** Active offset list in descending order (larger first). */
    val offsetsMinutes: List<Int>
        get() = buildList {
            if (offset30mEnabled) add(30)
            if (offset10mEnabled) add(10)
        }

    companion object {
        val DEFAULT = ScheduledReminderSettings(
            calendarEnabled         = true,
            todoistEnabled          = true,
            localEnabled            = true,
            offset30mEnabled        = true,
            offset10mEnabled        = true,
            notifyFallbackEnabled   = true,
            backgroundSpeechEnabled = false,
        )
    }
}
