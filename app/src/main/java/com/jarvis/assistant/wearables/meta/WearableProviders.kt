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

    /**
     * Open the Meta AI companion app's app-registration flow.  This is
     * the prerequisite for [connect] to find an eligible device — the
     * SDK can only see glasses that the user has explicitly approved
     * for this Android app (via its package name + signature).  Without
     * registration, `Wearables.createSession(...)` returns
     * `DeviceSessionError.NO_ELIGIBLE_DEVICE` and state stays
     * DISCONNECTED.
     *
     * @return true when the registration intent was launched, false
     *         when the SDK is absent / disabled / refused (stub +
     *         disabled providers always return false).
     */
    fun startRegistration(activity: android.app.Activity): Boolean = false

    /**
     * One-line summary of the current registration state for the
     * Settings UI.  Examples: "registered", "not registered",
     * "pending approval", "dev mode".  Default empty for backends
     * without a registry concept (stub / mock).
     */
    val registrationStatusLabel: String get() = ""

    /** Count of devices visible to the SDK (paired + reachable). */
    val visibleDeviceCount: Int get() = 0

    /**
     * One-line summary of the first visible device's link health
     * (e.g. "CONNECTED", "DISCONNECTED", "CONNECTING") — separate
     * from our own [stateFlow] which describes the SESSION state.
     * A device can be visible-but-disconnected, which is the most
     * common reason connect() fails despite registration succeeding.
     * Empty string when no devices visible or backend doesn't support.
     */
    val firstDeviceLinkLabel: String get() = ""

    /**
     * Compatibility of the first visible device.  Typical values:
     * "COMPATIBLE", "DEVICE_UPDATE_REQUIRED", "DAT_APP_UPDATE_REQUIRED".
     * When non-COMPATIBLE the session will hang at STARTING — the
     * provider short-circuits connect() and surfaces a clearer error.
     * Empty when no device visible / backend doesn't support.
     */
    val compatibilityLabel: String get() = ""

    /**
     * Glasses-side camera permission status ("GRANTED" / "DENIED" /
     * "UNKNOWN").  Distinct from Android's runtime CAMERA permission
     * — the DAT SDK has its own permission surface granted via Meta
     * AI.  Without this, addStream + Stream.start can hang because
     * the SDK is blocked waiting for the user to approve in Meta AI.
     */
    val cameraPermissionLabel: String get() = ""

    /** Glasses-side microphone permission status. */
    val microphonePermissionLabel: String get() = ""

    /**
     * Launch Meta AI's flow to grant the glasses-side CAMERA
     * permission to this app.  Returns true when an intent was
     * dispatched; false when SDK is absent / disabled.
     */
    fun requestCameraPermission(activity: android.app.Activity): Boolean = false

    /** Launch Meta AI's flow to grant glasses-side MICROPHONE permission. */
    fun requestMicrophonePermission(activity: android.app.Activity): Boolean = false

    /** Open Meta AI's firmware-update screen for the linked glasses. */
    fun openFirmwareUpdate(activity: android.app.Activity): Boolean = false

    /** Open Meta AI's DAT-app-update screen (for this App ID). */
    fun openDatAppUpdate(activity: android.app.Activity): Boolean = false
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
