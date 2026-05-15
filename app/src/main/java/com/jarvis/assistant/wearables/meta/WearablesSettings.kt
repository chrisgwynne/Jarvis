package com.jarvis.assistant.wearables.meta

/**
 * WearablesSettings — user policy for the Meta Wearables module.
 *
 * The actual feature content (interfaces, state machine, providers)
 * is platform-agnostic; *this* type is the policy the user toggles
 * in Settings.  Persisted via [com.jarvis.assistant.util.SettingsStore]
 * and exposed as a [kotlinx.coroutines.flow.StateFlow] via
 * [WearablesSettingsRepository].
 */
data class WearablesSettings(
    /** Master switch.  OFF (default) keeps the module dormant. */
    val enabled: Boolean,
    /** Use the in-memory mock backend (dev / instrumented test). */
    val useMockDevice: Boolean,
    /** Auto-connect on JarvisRuntime start. */
    val autoConnectOnStart: Boolean,
    /** Use glasses as the source for "look at this" / "what am I looking at". */
    val useForLookAtThis: Boolean,
    /** Save captured glasses media to the system gallery. */
    val saveCapturesToGallery: Boolean,
    /** Prefer glasses over phone camera when both are available. */
    val preferGlassesCamera: Boolean,
    /** Allow on-device vision analysis (OCR / object tags). */
    val visionAnalysisEnabled: Boolean,
    /** Prefer on-device vision over cloud / LLM-driven analysis. */
    val preferOnDeviceVision: Boolean,
    /** Allow cloud vision (e.g. multimodal LLM) when on-device can't. */
    val allowCloudVision: Boolean,
    /** Keep a local visual-history store. */
    val saveVisualHistory: Boolean,
    /** Visual history retention in days. */
    val visualHistoryRetentionDays: Int,
    /** Require confirmation before sending captured media to anyone. */
    val confirmBeforeSharing: Boolean,
    /** Require confirmation before saving a visual memory. */
    val confirmBeforeSavingMemory: Boolean,
) {
    companion object {
        /**
         * Conservative defaults: opt-in for everything.  The user has
         * to explicitly turn the feature on AND turn on the surfaces
         * they want — privacy by default.
         */
        val DEFAULT = WearablesSettings(
            enabled                    = false,
            useMockDevice              = false,
            autoConnectOnStart         = false,
            useForLookAtThis           = true,
            saveCapturesToGallery      = true,
            preferGlassesCamera        = true,
            visionAnalysisEnabled      = true,
            preferOnDeviceVision       = true,
            allowCloudVision           = false,
            saveVisualHistory          = false,
            visualHistoryRetentionDays = 7,
            confirmBeforeSharing       = true,
            confirmBeforeSavingMemory  = true,
        )
    }
}
