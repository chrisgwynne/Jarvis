package com.jarvis.assistant.ambient

import com.jarvis.assistant.util.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AmbientSettingsRepository(private val store: SettingsStore) {

    private val _state = MutableStateFlow(load())
    val stateFlow: StateFlow<AmbientSettings> = _state.asStateFlow()

    fun snapshot(): AmbientSettings = _state.value

    fun setEnabled(v: Boolean)                   { store.ambientEnabled = v;                   reload() }
    fun setLearningEnabled(v: Boolean)            { store.ambientLearningEnabled = v;            reload() }
    fun setLocationSuggestionsEnabled(v: Boolean) { store.ambientLocationSuggestionsEnabled = v; reload() }
    fun setAppContextSuggestionsEnabled(v: Boolean){ store.ambientAppContextSuggestionsEnabled = v; reload() }
    fun setHomeAssistantAlertsEnabled(v: Boolean) { store.ambientHomeAssistantAlertsEnabled = v; reload() }
    fun setTravelSuggestionsEnabled(v: Boolean)   { store.ambientTravelSuggestionsEnabled = v;   reload() }
    fun setCustomerWorkNudgesEnabled(v: Boolean)  { store.ambientCustomerWorkNudgesEnabled = v;  reload() }
    fun setLearnFromDismissalsEnabled(v: Boolean) { store.ambientLearnFromDismissalsEnabled = v; reload() }
    fun setMinConfidenceToSpeak(v: Float)         { store.ambientMinConfidenceToSpeak = v;       reload() }
    fun setMaxNudgesPerDay(v: Int)                { store.ambientMaxNudgesPerDay = v;            reload() }

    fun resetToDefaults() {
        val d = AmbientSettings()
        store.ambientEnabled                      = d.enabled
        store.ambientLearningEnabled              = d.learningEnabled
        store.ambientLocationSuggestionsEnabled   = d.locationSuggestionsEnabled
        store.ambientAppContextSuggestionsEnabled = d.appContextSuggestionsEnabled
        store.ambientHomeAssistantAlertsEnabled   = d.homeAssistantAlertsEnabled
        store.ambientTravelSuggestionsEnabled     = d.travelSuggestionsEnabled
        store.ambientCustomerWorkNudgesEnabled    = d.customerWorkNudgesEnabled
        store.ambientLearnFromDismissalsEnabled   = d.learnFromDismissalsEnabled
        store.ambientMinConfidenceToSpeak         = d.minConfidenceToSpeak
        store.ambientMaxNudgesPerDay              = d.maxNudgesPerDay
        reload()
    }

    private fun load(): AmbientSettings = AmbientSettings(
        enabled                      = store.ambientEnabled,
        learningEnabled              = store.ambientLearningEnabled,
        locationSuggestionsEnabled   = store.ambientLocationSuggestionsEnabled,
        appContextSuggestionsEnabled = store.ambientAppContextSuggestionsEnabled,
        homeAssistantAlertsEnabled   = store.ambientHomeAssistantAlertsEnabled,
        travelSuggestionsEnabled     = store.ambientTravelSuggestionsEnabled,
        customerWorkNudgesEnabled    = store.ambientCustomerWorkNudgesEnabled,
        learnFromDismissalsEnabled   = store.ambientLearnFromDismissalsEnabled,
        minConfidenceToSpeak         = store.ambientMinConfidenceToSpeak,
        maxNudgesPerDay              = store.ambientMaxNudgesPerDay,
    )

    private fun reload() { _state.value = load() }
}
