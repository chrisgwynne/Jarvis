package com.jarvis.assistant.wearables.meta

import kotlinx.coroutines.flow.Flow

/**
 * WearableDeviceProvider — connection / device-lifecycle surface.
 *
 * One concrete implementation per backend:
 *   - [StubMetaWearablesProvider] — returned when the DAT SDK is
 *     not present.  Reports SDK_UNAVAILABLE and refuses every call
 *     gracefully.
 *   - [MockMetaWearablesProvider] — pure in-memory simulation for
 *     dev / instrumented tests.
 *   - `RealMetaWearablesProvider` — added when the DAT SDK Maven
 *     coordinates are wired in (deferred to PR4.x; see
 *     `docs/wearables/meta-dat-integration.md`).
 *
 * All methods are suspend-safe and MUST NOT throw — surface failures
 * via the [stateFlow] (transitioning to ERROR) and / or return a
 * sealed-typed Result.  This is the contract that lets the rest of
 * Jarvis treat the wearables subsystem as optional.
 */
interface WearableDeviceProvider {

    /** Live state stream consumed by Settings UI + the runtime. */
    val stateFlow: Flow<MetaWearablesState>
    val currentState: MetaWearablesState

    /** Optional device label (e.g. "Ray-Ban Meta") when connected. */
    val deviceName: String?

    /** Optional battery percentage 0..100 when the device reports it. */
    val batteryPercent: Int?

    /** Last error message (already user-safe via UserSafeErrorHandler). */
    val lastError: String?

    /**
     * Attempt connection.  Idempotent — calling when CONNECTING /
     * CONNECTED is a no-op.  Returns true when the call moved state
     * forward (or kept it at CONNECTED); false on a hard failure.
     */
    suspend fun connect(): Boolean

    /** Disconnect, releasing any camera session. */
    suspend fun disconnect()

    /** Test-only: simulate a transport-level disconnect (used by mock + diagnostics). */
    suspend fun simulateDisconnect() {}
}

/**
 * WearableCameraProvider — capture surface.
 *
 * Separated from [WearableDeviceProvider] so the test harness can
 * pin a tiny, deterministic camera that doesn't need a fake
 * Bluetooth state machine.
 */
interface WearableCameraProvider {

    /** Open a camera session.  Requires the device to be CONNECTED. */
    suspend fun startCameraSession(): Boolean

    /** Close the camera session.  Safe to call from any state. */
    suspend fun stopCameraSession()

    /**
     * Capture one photo.  Returns the local file path / content URI
     * where the JPEG / HEIC was saved, or null on failure.  The
     * caller is responsible for publishing the URI to
     * [com.jarvis.assistant.tools.device.media.MediaContextStore]
     * so the existing "show that" / "send that" flows work.
     */
    suspend fun capturePhoto(): String?

    /**
     * Start a streaming session.  Frames are delivered to [onFrame].
     * The callback returns true to keep streaming, false to stop.
     * Pure-data callback — implementations push raw bytes; the
     * vision pipeline owns decoding + analysis.
     */
    suspend fun startStream(onFrame: (frameBytes: ByteArray, widthPx: Int, heightPx: Int) -> Boolean): Boolean

    /** Stop the streaming session if running. */
    suspend fun stopStream()
}

/**
 * WearableContextProvider — exposes the most-recent visual context
 * captured by the glasses (or the mock).  Read by the
 * RecentContextEngine / ContextualFollowupResolver so phrases like
 * "show that" / "send that to Mike" / "remind me about this later"
 * find the latest glasses capture.
 */
interface WearableContextProvider {
    /** Latest captured visual context, or null when none in window. */
    fun peekRecent(): RecentVisualContext?

    /** Drop the current context (e.g. "forget that"). */
    fun clearRecent()
}
