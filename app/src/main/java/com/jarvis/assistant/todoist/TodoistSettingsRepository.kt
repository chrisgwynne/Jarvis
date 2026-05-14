package com.jarvis.assistant.todoist

import android.util.Log
import com.jarvis.assistant.util.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin wrapper around [SettingsStore] that produces immutable
 * [TodoistSettings] snapshots and exposes a [stateFlow] for the UI.
 *
 * Mirrors [com.jarvis.assistant.proactive.settings.ProactivitySettingsRepository]
 * and [com.jarvis.assistant.remote.openclaw.OpenClawSettingsRepository]
 * by intent.
 */
class TodoistSettingsRepository(private val store: SettingsStore) {

    private val _stateFlow = MutableStateFlow(snapshot())
    val stateFlow: StateFlow<TodoistSettings> = _stateFlow.asStateFlow()

    init {
        val s = _stateFlow.value
        Log.d(TAG, "[TODOIST_SETTINGS_LOADED] enabled=${s.enabled} " +
            "hasToken=${s.apiToken.isNotBlank()} " +
            "defaultProject='${s.defaultProjectId}' " +
            "defaultPriority=${s.defaultPriority} " +
            "offlineSync=${s.offlineSyncEnabled}")
    }

    fun snapshot(): TodoistSettings = TodoistSettings(
        enabled                       = store.todoistEnabled,
        apiToken                      = store.todoistApiToken,
        defaultProjectId              = store.todoistDefaultProjectId,
        defaultLabels                 = store.todoistDefaultLabelsCsv
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() },
        defaultPriority               = store.todoistDefaultPriority,
        defaultReminderMinuteOfDay    = store.todoistDefaultReminderMinuteOfDay,
        askForLabelAfterCreate        = store.todoistAskForLabelAfterCreate,
        askForTimeWhenDateVague       = store.todoistAskForTimeWhenDateVague,
        offlineSyncEnabled            = store.todoistOfflineSyncEnabled,
        voiceConfirmationsEnabled     = store.todoistVoiceConfirmationsEnabled,
        smartFollowUpEnabled          = store.todoistSmartFollowUpEnabled,
        contextualRemindersEnabled    = store.todoistContextualRemindersEnabled,
        repeatingReminderNudgesEnabled = store.todoistRepeatingNudgesEnabled,
    )

    fun refresh() { _stateFlow.value = snapshot() }

    fun setEnabled(v: Boolean)                       { store.todoistEnabled = v; refresh() }
    fun setApiToken(v: String)                       { store.todoistApiToken = v; refresh() }
    fun setDefaultProjectId(v: String)               { store.todoistDefaultProjectId = v; refresh() }
    fun setDefaultLabels(labels: List<String>)       {
        store.todoistDefaultLabelsCsv = labels.joinToString(",") { it.trim() }
        refresh()
    }
    fun setDefaultPriority(v: TodoistPriority)       { store.todoistDefaultPriority = v; refresh() }
    fun setDefaultReminderMinute(v: Int)             { store.todoistDefaultReminderMinuteOfDay = v; refresh() }
    fun setAskForLabel(v: Boolean)                   { store.todoistAskForLabelAfterCreate = v; refresh() }
    fun setAskForTime(v: Boolean)                    { store.todoistAskForTimeWhenDateVague = v; refresh() }
    fun setOfflineSync(v: Boolean)                   { store.todoistOfflineSyncEnabled = v; refresh() }
    fun setVoiceConfirmations(v: Boolean)            { store.todoistVoiceConfirmationsEnabled = v; refresh() }
    fun setSmartFollowUp(v: Boolean)                 { store.todoistSmartFollowUpEnabled = v; refresh() }
    fun setContextualReminders(v: Boolean)           { store.todoistContextualRemindersEnabled = v; refresh() }
    fun setRepeatingNudges(v: Boolean)               { store.todoistRepeatingNudgesEnabled = v; refresh() }

    fun resetToDefaults() {
        val d = TodoistSettings.DEFAULT
        store.todoistEnabled                       = d.enabled
        store.todoistApiToken                      = d.apiToken
        store.todoistDefaultProjectId              = d.defaultProjectId
        store.todoistDefaultLabelsCsv              = d.defaultLabels.joinToString(",")
        store.todoistDefaultPriority               = d.defaultPriority
        store.todoistDefaultReminderMinuteOfDay    = d.defaultReminderMinuteOfDay
        store.todoistAskForLabelAfterCreate        = d.askForLabelAfterCreate
        store.todoistAskForTimeWhenDateVague       = d.askForTimeWhenDateVague
        store.todoistOfflineSyncEnabled            = d.offlineSyncEnabled
        store.todoistVoiceConfirmationsEnabled     = d.voiceConfirmationsEnabled
        store.todoistSmartFollowUpEnabled          = d.smartFollowUpEnabled
        store.todoistContextualRemindersEnabled    = d.contextualRemindersEnabled
        store.todoistRepeatingNudgesEnabled        = d.repeatingReminderNudgesEnabled
        refresh()
    }

    companion object { private const val TAG = "TodoistRepo" }
}
