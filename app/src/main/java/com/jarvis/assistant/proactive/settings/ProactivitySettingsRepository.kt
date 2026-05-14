package com.jarvis.assistant.proactive.settings

import android.util.Log
import com.jarvis.assistant.util.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ProactivitySettingsRepository — single source of truth for the
 * Proactivity settings screen and the ProactiveEngine.
 *
 * Mirrors the [com.jarvis.assistant.remote.openclaw.OpenClawSettingsRepository]
 * pattern: thin wrapper over [SettingsStore] with a read-through
 * [snapshot] and a [stateFlow] for UI binding.  Every write helper:
 *
 *   1. updates [SettingsStore] (disk),
 *   2. emits the new snapshot to [stateFlow] so Compose recomposes,
 *   3. logs `[PROACTIVITY_SETTINGS_LOADED]` on construction so a log
 *      audit can confirm the values the engine is running with.
 *
 * The repository is safe to instantiate multiple times — every instance
 * reads back from the same prefs — but the standard wiring keeps a single
 * instance held by [com.jarvis.assistant.runtime.JarvisRuntime] and
 * surfaced to the UI via the existing settings ViewModel pattern.
 */
class ProactivitySettingsRepository(private val store: SettingsStore) {

    private val _stateFlow = MutableStateFlow(snapshot())
    val stateFlow: StateFlow<ProactivitySettings> = _stateFlow.asStateFlow()

    init {
        val s = _stateFlow.value
        Log.d(TAG, "[PROACTIVITY_SETTINGS_LOADED] enabled=${s.enabled} " +
            "mode=${s.interruptionMode} sensitivity=${s.sensitivity} " +
            "quietHours=${s.quietHoursEnabled} (${s.quietStartMinute}→${s.quietEndMinute}) " +
            "cooldownMin=${s.globalCooldownMinutes}")
    }

    /** Read every field off [SettingsStore] into an immutable snapshot. */
    fun snapshot(): ProactivitySettings = ProactivitySettings(
        enabled                     = store.proactivityEnabled,
        quietHoursEnabled           = store.proactivityQuietHoursEnabled,
        quietStartMinute            = store.proactivityQuietStartMinute,
        quietEndMinute              = store.proactivityQuietEndMinute,
        allowUrgentDuringQuietHours = store.proactivityAllowUrgentDuringQuietHours,
        suggestionsEnabled          = store.proactivitySuggestionsEnabled,
        remindersEnabled            = store.proactivityRemindersEnabled,
        locationAlertsEnabled       = store.proactivityLocationAlertsEnabled,
        homeAssistantAlertsEnabled  = store.proactivityHomeAssistantAlertsEnabled,
        calendarNudgesEnabled       = store.proactivityCalendarNudgesEnabled,
        learningObservationsEnabled = store.proactivityLearningObservationsEnabled,
        safetySecurityAlertsEnabled = store.proactivitySafetySecurityAlertsEnabled,
        interruptionMode            = store.proactivityInterruptionMode,
        sensitivity                 = store.proactivitySensitivity,
        globalCooldownMinutes       = store.proactivityGlobalCooldownMinutes,
    )

    /** Re-read from disk and emit.  Useful when something outside the
     *  repository writes the underlying [SettingsStore]. */
    fun refresh() { _stateFlow.value = snapshot() }

    // ── Setters ───────────────────────────────────────────────────────────

    fun setEnabled(v: Boolean) {
        store.proactivityEnabled = v
        refresh()
    }

    fun setQuietHoursEnabled(v: Boolean) {
        store.proactivityQuietHoursEnabled = v
        refresh()
    }

    fun setQuietStartMinute(v: Int) {
        store.proactivityQuietStartMinute = v
        refresh()
    }

    fun setQuietEndMinute(v: Int) {
        store.proactivityQuietEndMinute = v
        refresh()
    }

    fun setAllowUrgentDuringQuietHours(v: Boolean) {
        store.proactivityAllowUrgentDuringQuietHours = v
        refresh()
    }

    fun setSuggestionsEnabled(v: Boolean) {
        store.proactivitySuggestionsEnabled = v
        refresh()
    }

    fun setRemindersEnabled(v: Boolean) {
        store.proactivityRemindersEnabled = v
        refresh()
    }

    fun setLocationAlertsEnabled(v: Boolean) {
        store.proactivityLocationAlertsEnabled = v
        refresh()
    }

    fun setHomeAssistantAlertsEnabled(v: Boolean) {
        store.proactivityHomeAssistantAlertsEnabled = v
        refresh()
    }

    fun setCalendarNudgesEnabled(v: Boolean) {
        store.proactivityCalendarNudgesEnabled = v
        refresh()
    }

    fun setLearningObservationsEnabled(v: Boolean) {
        store.proactivityLearningObservationsEnabled = v
        refresh()
    }

    fun setSafetySecurityAlertsEnabled(v: Boolean) {
        store.proactivitySafetySecurityAlertsEnabled = v
        refresh()
    }

    fun setInterruptionMode(v: InterruptionMode) {
        store.proactivityInterruptionMode = v
        refresh()
    }

    fun setSensitivity(v: ProactivitySensitivity) {
        store.proactivitySensitivity = v
        refresh()
    }

    fun setGlobalCooldownMinutes(v: Int) {
        store.proactivityGlobalCooldownMinutes = v
        refresh()
    }

    /** Reset every field to [ProactivitySettings.DEFAULT]. */
    fun resetToDefaults() {
        val d = ProactivitySettings.DEFAULT
        store.proactivityEnabled                       = d.enabled
        store.proactivityQuietHoursEnabled             = d.quietHoursEnabled
        store.proactivityQuietStartMinute              = d.quietStartMinute
        store.proactivityQuietEndMinute                = d.quietEndMinute
        store.proactivityAllowUrgentDuringQuietHours   = d.allowUrgentDuringQuietHours
        store.proactivitySuggestionsEnabled            = d.suggestionsEnabled
        store.proactivityRemindersEnabled              = d.remindersEnabled
        store.proactivityLocationAlertsEnabled         = d.locationAlertsEnabled
        store.proactivityHomeAssistantAlertsEnabled    = d.homeAssistantAlertsEnabled
        store.proactivityCalendarNudgesEnabled         = d.calendarNudgesEnabled
        store.proactivityLearningObservationsEnabled   = d.learningObservationsEnabled
        store.proactivitySafetySecurityAlertsEnabled   = d.safetySecurityAlertsEnabled
        store.proactivityInterruptionMode              = d.interruptionMode
        store.proactivitySensitivity                   = d.sensitivity
        store.proactivityGlobalCooldownMinutes         = d.globalCooldownMinutes
        refresh()
    }

    companion object { private const val TAG = "ProactivityRepo" }
}
