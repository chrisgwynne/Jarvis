package com.jarvis.assistant.trust

import com.jarvis.assistant.util.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Snapshot of the user's Trust & Autonomy preferences.
 * Read by [AutonomyEngine] on every evaluation call.
 */
data class AutonomySettings(
    val preset: AutonomyPreset = AutonomyPreset.BALANCED,

    // Per-category overrides
    val requireConfirmForMessages: Boolean = false,
    val requireConfirmForCalls: Boolean = false,
    val requireConfirmForMediaShare: Boolean = false,

    // Context-sensitive modes
    val lockscreenRestrictions: Boolean = true,
    val carModeAutonomy: Boolean = true,
    val headphonesPrivateMode: Boolean = true,
    val homeTrustedMode: Boolean = true,

    // Voice trust integration
    val voiceTrustEnabled: Boolean = true,
) {
    companion object {
        val DEFAULT = AutonomySettings()
    }
}

/**
 * Reads and writes [AutonomySettings] from [SettingsStore].
 *
 * Exposes a [StateFlow] for real-time observation by the diagnostics UI.
 */
class AutonomySettingsRepository(private val store: SettingsStore) {

    private val _flow = MutableStateFlow(read())
    val settingsFlow: StateFlow<AutonomySettings> = _flow.asStateFlow()

    fun snapshot(): AutonomySettings = _flow.value

    fun setPreset(preset: AutonomyPreset) {
        store.autonomyPreset = preset.name
        refresh()
    }

    fun setRequireConfirmForMessages(v: Boolean) {
        store.autonomyConfirmMessages = v; refresh()
    }

    fun setRequireConfirmForCalls(v: Boolean) {
        store.autonomyConfirmCalls = v; refresh()
    }

    fun setRequireConfirmForMediaShare(v: Boolean) {
        store.autonomyConfirmMediaShare = v; refresh()
    }

    fun setLockscreenRestrictions(v: Boolean) {
        store.autonomyLockscreenRestrictions = v; refresh()
    }

    fun setCarModeAutonomy(v: Boolean) {
        store.autonomyCarMode = v; refresh()
    }

    fun setHeadphonesPrivateMode(v: Boolean) {
        store.autonomyHeadphonesPrivate = v; refresh()
    }

    fun setHomeTrustedMode(v: Boolean) {
        store.autonomyHomeTrusted = v; refresh()
    }

    fun setVoiceTrustEnabled(v: Boolean) {
        store.autonomyVoiceTrust = v; refresh()
    }

    private fun read() = AutonomySettings(
        preset                    = runCatching {
            AutonomyPreset.valueOf(store.autonomyPreset)
        }.getOrDefault(AutonomyPreset.BALANCED),
        requireConfirmForMessages = store.autonomyConfirmMessages,
        requireConfirmForCalls    = store.autonomyConfirmCalls,
        requireConfirmForMediaShare = store.autonomyConfirmMediaShare,
        lockscreenRestrictions    = store.autonomyLockscreenRestrictions,
        carModeAutonomy           = store.autonomyCarMode,
        headphonesPrivateMode     = store.autonomyHeadphonesPrivate,
        homeTrustedMode           = store.autonomyHomeTrusted,
        voiceTrustEnabled         = store.autonomyVoiceTrust,
    )

    private fun refresh() { _flow.value = read() }
}
