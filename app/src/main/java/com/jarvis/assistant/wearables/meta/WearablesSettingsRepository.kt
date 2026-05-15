package com.jarvis.assistant.wearables.meta

import com.jarvis.assistant.util.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * WearablesSettingsRepository — SettingsStore-backed wrapper exposing
 * [WearablesSettings] as a live [StateFlow] for the Compose UI and a
 * cheap [snapshot] for the runtime / manager.
 *
 * Mirrors the [com.jarvis.assistant.proactive.settings
 * .ProactivitySettingsRepository] pattern.
 *
 * Writes pass through [SettingsStore] so they survive process death;
 * each write also re-emits to [stateFlow] so the UI recomposes
 * immediately.
 *
 * When the user toggles [setEnabled] / [setUseMockDevice], the
 * caller (the Compose screen or the runtime) is responsible for
 * calling [MetaWearablesManager.refresh] so the backend swap takes
 * effect.  The repo itself does not hold a manager reference to keep
 * the testing surface narrow.
 */
class WearablesSettingsRepository(private val store: SettingsStore) {

    private val _stateFlow = MutableStateFlow(snapshot())
    val stateFlow: StateFlow<WearablesSettings> = _stateFlow.asStateFlow()

    fun snapshot(): WearablesSettings = WearablesSettings(
        enabled                    = store.wearablesEnabled,
        useMockDevice              = store.wearablesUseMockDevice,
        autoConnectOnStart         = store.wearablesAutoConnectOnStart,
        useForLookAtThis           = store.wearablesUseForLookAtThis,
        saveCapturesToGallery      = store.wearablesSaveCapturesToGallery,
        preferGlassesCamera        = store.wearablesPreferGlassesCamera,
        visionAnalysisEnabled      = store.wearablesVisionAnalysisEnabled,
        preferOnDeviceVision       = store.wearablesPreferOnDeviceVision,
        allowCloudVision           = store.wearablesAllowCloudVision,
        saveVisualHistory          = store.wearablesSaveVisualHistory,
        visualHistoryRetentionDays = store.wearablesVisualHistoryRetentionDays,
        confirmBeforeSharing       = store.wearablesConfirmBeforeSharing,
        confirmBeforeSavingMemory  = store.wearablesConfirmBeforeSavingMemory,
    )

    private fun refresh() { _stateFlow.value = snapshot() }

    fun setEnabled(v: Boolean)                  { store.wearablesEnabled = v; refresh() }
    fun setUseMockDevice(v: Boolean)            { store.wearablesUseMockDevice = v; refresh() }
    fun setAutoConnectOnStart(v: Boolean)       { store.wearablesAutoConnectOnStart = v; refresh() }
    fun setUseForLookAtThis(v: Boolean)         { store.wearablesUseForLookAtThis = v; refresh() }
    fun setSaveCapturesToGallery(v: Boolean)    { store.wearablesSaveCapturesToGallery = v; refresh() }
    fun setPreferGlassesCamera(v: Boolean)      { store.wearablesPreferGlassesCamera = v; refresh() }
    fun setVisionAnalysisEnabled(v: Boolean)    { store.wearablesVisionAnalysisEnabled = v; refresh() }
    fun setPreferOnDeviceVision(v: Boolean)     { store.wearablesPreferOnDeviceVision = v; refresh() }
    fun setAllowCloudVision(v: Boolean)         { store.wearablesAllowCloudVision = v; refresh() }
    fun setSaveVisualHistory(v: Boolean)        { store.wearablesSaveVisualHistory = v; refresh() }
    fun setVisualHistoryRetentionDays(v: Int)   { store.wearablesVisualHistoryRetentionDays = v; refresh() }
    fun setConfirmBeforeSharing(v: Boolean)     { store.wearablesConfirmBeforeSharing = v; refresh() }
    fun setConfirmBeforeSavingMemory(v: Boolean){ store.wearablesConfirmBeforeSavingMemory = v; refresh() }

    fun resetToDefaults() {
        val d = WearablesSettings.DEFAULT
        store.wearablesEnabled                    = d.enabled
        store.wearablesUseMockDevice              = d.useMockDevice
        store.wearablesAutoConnectOnStart         = d.autoConnectOnStart
        store.wearablesUseForLookAtThis           = d.useForLookAtThis
        store.wearablesSaveCapturesToGallery      = d.saveCapturesToGallery
        store.wearablesPreferGlassesCamera        = d.preferGlassesCamera
        store.wearablesVisionAnalysisEnabled      = d.visionAnalysisEnabled
        store.wearablesPreferOnDeviceVision       = d.preferOnDeviceVision
        store.wearablesAllowCloudVision           = d.allowCloudVision
        store.wearablesSaveVisualHistory          = d.saveVisualHistory
        store.wearablesVisualHistoryRetentionDays = d.visualHistoryRetentionDays
        store.wearablesConfirmBeforeSharing       = d.confirmBeforeSharing
        store.wearablesConfirmBeforeSavingMemory  = d.confirmBeforeSavingMemory
        refresh()
    }
}
