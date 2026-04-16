package com.jarvis.assistant.remote.openclaw

import com.jarvis.assistant.util.SettingsStore

/**
 * Thin wrapper around [SettingsStore] that produces [OpenClawSettings] snapshots.
 * Keeps OpenClaw logic decoupled from the full SettingsStore.
 */
class OpenClawSettingsRepository(private val store: SettingsStore) {

    /** Read the current settings into an immutable snapshot. */
    fun snapshot(): OpenClawSettings = OpenClawSettings(
        enabled   = store.openClawEnabled,
        host      = store.openClawHost,
        port      = store.openClawPort,
        secure    = store.openClawSecure,
        authToken = store.openClawAuthToken,
        timeoutMs = store.openClawTimeoutMs
    )

    /** True when OpenClaw is enabled AND a host has been entered. */
    fun isConfigured(): Boolean = snapshot().isFullyConfigured
}
