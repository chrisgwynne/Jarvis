package com.jarvis.assistant.personality

import com.jarvis.assistant.util.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * PersonalitySettingsRepository — thin wrapper around [SettingsStore]
 * that exposes the live [PersonalitySettings] snapshot for both the
 * Compose UI (via [stateFlow]) and the runtime selectors / template
 * engine (via [snapshot]).
 *
 * Mirrors the [com.jarvis.assistant.proactive.settings
 * .ProactivitySettingsRepository] pattern so the wiring is familiar.
 */
class PersonalitySettingsRepository(private val store: SettingsStore) {

    private val _stateFlow = MutableStateFlow(snapshot())
    val stateFlow: StateFlow<PersonalitySettings> = _stateFlow.asStateFlow()

    fun snapshot(): PersonalitySettings = PersonalitySettings(
        enabled                      = store.personalityEnabled,
        sarcasm                      = store.personalitySarcasmLevel,
        jokeFrequency                = store.personalityJokeFrequency,
        pushbackEnabled              = store.personalityPushbackEnabled,
        friendlyRoastingEnabled      = store.personalityFriendlyRoastingEnabled,
        seriousModeAutoDetectEnabled = store.personalitySeriousAutoDetectEnabled,
        applyToProactiveReminders    = store.personalityApplyToProactiveReminders,
        applyToLocalConfirmations    = store.personalityApplyToLocalConfirmations,
        applyToLlmAnswers            = store.personalityApplyToLlmAnswers,
    )

    private fun refresh() { _stateFlow.value = snapshot() }

    fun setEnabled(v: Boolean)                      { store.personalityEnabled = v; refresh() }
    fun setSarcasm(v: SarcasmLevel)                 { store.personalitySarcasmLevel = v; refresh() }
    fun setJokeFrequency(v: JokeFrequency)          { store.personalityJokeFrequency = v; refresh() }
    fun setPushbackEnabled(v: Boolean)              { store.personalityPushbackEnabled = v; refresh() }
    fun setFriendlyRoastingEnabled(v: Boolean)      { store.personalityFriendlyRoastingEnabled = v; refresh() }
    fun setSeriousModeAutoDetect(v: Boolean)        { store.personalitySeriousAutoDetectEnabled = v; refresh() }
    fun setApplyToProactiveReminders(v: Boolean)    { store.personalityApplyToProactiveReminders = v; refresh() }
    fun setApplyToLocalConfirmations(v: Boolean)    { store.personalityApplyToLocalConfirmations = v; refresh() }
    fun setApplyToLlmAnswers(v: Boolean)            { store.personalityApplyToLlmAnswers = v; refresh() }

    fun resetToDefaults() {
        val d = PersonalitySettings.DEFAULT
        store.personalityEnabled                    = d.enabled
        store.personalitySarcasmLevel               = d.sarcasm
        store.personalityJokeFrequency              = d.jokeFrequency
        store.personalityPushbackEnabled            = d.pushbackEnabled
        store.personalityFriendlyRoastingEnabled    = d.friendlyRoastingEnabled
        store.personalitySeriousAutoDetectEnabled   = d.seriousModeAutoDetectEnabled
        store.personalityApplyToProactiveReminders  = d.applyToProactiveReminders
        store.personalityApplyToLocalConfirmations  = d.applyToLocalConfirmations
        store.personalityApplyToLlmAnswers          = d.applyToLlmAnswers
        refresh()
    }
}
