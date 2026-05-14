package com.jarvis.assistant.proactive.scheduled

import com.jarvis.assistant.util.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ScheduledReminderSettingsRepository — thin wrapper around
 * [SettingsStore] that exposes a single [ScheduledReminderSettings]
 * snapshot for both the UI (Compose) and the engine.  Mirrors the
 * [com.jarvis.assistant.proactive.settings.ProactivitySettingsRepository]
 * pattern.
 */
class ScheduledReminderSettingsRepository(private val store: SettingsStore) {

    private val _stateFlow = MutableStateFlow(snapshot())
    val stateFlow: StateFlow<ScheduledReminderSettings> = _stateFlow.asStateFlow()

    fun snapshot(): ScheduledReminderSettings = ScheduledReminderSettings(
        calendarEnabled         = store.schedRemindersCalendarEnabled,
        todoistEnabled          = store.schedRemindersTodoistEnabled,
        localEnabled            = store.schedRemindersLocalEnabled,
        offset30mEnabled        = store.schedReminders30mEnabled,
        offset10mEnabled        = store.schedReminders10mEnabled,
        notifyFallbackEnabled   = store.schedRemindersNotifyFallback,
        backgroundSpeechEnabled = store.schedRemindersBackgroundSpeech,
    )

    private fun refresh() { _stateFlow.value = snapshot() }

    fun setCalendarEnabled(v: Boolean)         { store.schedRemindersCalendarEnabled = v; refresh() }
    fun setTodoistEnabled(v: Boolean)          { store.schedRemindersTodoistEnabled = v; refresh() }
    fun setLocalEnabled(v: Boolean)            { store.schedRemindersLocalEnabled = v; refresh() }
    fun setOffset30mEnabled(v: Boolean)        { store.schedReminders30mEnabled = v; refresh() }
    fun setOffset10mEnabled(v: Boolean)        { store.schedReminders10mEnabled = v; refresh() }
    fun setNotifyFallbackEnabled(v: Boolean)   { store.schedRemindersNotifyFallback = v; refresh() }
    fun setBackgroundSpeechEnabled(v: Boolean) { store.schedRemindersBackgroundSpeech = v; refresh() }

    fun resetToDefaults() {
        val d = ScheduledReminderSettings.DEFAULT
        store.schedRemindersCalendarEnabled = d.calendarEnabled
        store.schedRemindersTodoistEnabled  = d.todoistEnabled
        store.schedRemindersLocalEnabled    = d.localEnabled
        store.schedReminders30mEnabled      = d.offset30mEnabled
        store.schedReminders10mEnabled      = d.offset10mEnabled
        store.schedRemindersNotifyFallback  = d.notifyFallbackEnabled
        store.schedRemindersBackgroundSpeech= d.backgroundSpeechEnabled
        refresh()
    }
}
