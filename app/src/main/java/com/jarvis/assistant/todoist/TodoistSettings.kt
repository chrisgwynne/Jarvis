package com.jarvis.assistant.todoist

/**
 * User-visible Todoist integration settings.
 *
 * Everything here drives one of three things:
 *   1. Whether the [TodoistClient] is allowed to make network calls at
 *      all ([enabled] + a non-blank [apiToken]).
 *   2. How the reminder parser fills in defaults when slots are missing
 *      (default project, label, priority, time).
 *   3. The conversational tone of confirmations + follow-up prompts.
 *
 * Persisted via [TodoistSettingsRepository] over
 * [com.jarvis.assistant.util.SettingsStore].
 */
data class TodoistSettings(
    val enabled: Boolean,
    val apiToken: String,
    val defaultProjectId: String,
    val defaultLabels: List<String>,
    val defaultPriority: TodoistPriority,
    /** When date is given but time isn't, fall back to this minute-of-day. */
    val defaultReminderMinuteOfDay: Int,
    val askForLabelAfterCreate: Boolean,
    val askForTimeWhenDateVague: Boolean,
    val offlineSyncEnabled: Boolean,
    val voiceConfirmationsEnabled: Boolean,
    val smartFollowUpEnabled: Boolean,
    val contextualRemindersEnabled: Boolean,
    val repeatingReminderNudgesEnabled: Boolean,
) {
    val isFullyConfigured: Boolean
        get() = enabled && apiToken.isNotBlank()

    companion object {
        val DEFAULT = TodoistSettings(
            enabled                       = false,
            apiToken                      = "",
            defaultProjectId              = "",
            defaultLabels                 = emptyList(),
            defaultPriority               = TodoistPriority.LOW,
            defaultReminderMinuteOfDay    = 9 * 60,   // 09:00
            askForLabelAfterCreate        = false,
            askForTimeWhenDateVague       = true,
            offlineSyncEnabled            = true,
            voiceConfirmationsEnabled     = true,
            smartFollowUpEnabled          = true,
            contextualRemindersEnabled    = true,
            repeatingReminderNudgesEnabled = true,
        )
    }
}
