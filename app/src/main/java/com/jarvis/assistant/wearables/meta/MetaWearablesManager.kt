package com.jarvis.assistant.wearables.meta

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * MetaWearablesManager — the single entry point the rest of Jarvis
 * uses to talk to the Meta Wearables module.  Holds one
 * [WearableDeviceProvider] / [WearableCameraProvider] /
 * [WearableContextProvider] (all three are the same object today —
 * the stub and the mock implement all three) chosen at construction
 * time based on:
 *
 *   1. Is the Meta DAT SDK present on the classpath?  (Detection
 *      is a `Class.forName("com.facebook.mwdat.core.MwDatClient")`
 *      probe; success → use the real backend.  Today the real
 *      backend doesn't exist yet, so this branch is left as a
 *      `TODO(...)` slot in [pickProvider].)
 *   2. Is the developer's "Use mock Meta glasses" toggle ON?  →
 *      [MockMetaWearablesProvider].
 *   3. Otherwise → [StubMetaWearablesProvider] (state pinned at
 *      SDK_UNAVAILABLE).
 *
 * Why three interfaces, one object today: the real DAT SDK exposes
 * device + camera + context as distinct sessions, so the interface
 * split mirrors how the real implementation will be structured.
 * Today the stub and mock implement all three for convenience.
 *
 * Wired as a JarvisApp-level singleton (see JarvisApp.metaWearables);
 * the Settings UI reads `manager.stateFlow` directly and the runtime
 * dispatches capture commands via [capturePhoto] / [startCameraSession].
 */
class MetaWearablesManager(
    private val context: Context,
    private val settingsProvider: () -> WearablesSettings,
) {

    /**
     * Currently-active backend.  Re-evaluated whenever the user
     * toggles "Meta Wearables enabled" / "Use mock device" — call
     * [refresh] from the settings repository on every write.
     */
    @Volatile private var providerImpl: WearableDeviceProvider = pickProvider().also {
        Log.d(TAG, "[META_WEARABLES_BACKEND] initial=${it::class.simpleName} state=${it.currentState}")
    }

    val deviceProvider:  WearableDeviceProvider  get() = providerImpl
    val cameraProvider:  WearableCameraProvider  get() = providerImpl as WearableCameraProvider
    val contextProvider: WearableContextProvider get() = providerImpl as WearableContextProvider

    val stateFlow: Flow<MetaWearablesState>  get() = deviceProvider.stateFlow

    /** Latest state without subscribing to the flow. */
    val currentState: MetaWearablesState get() = deviceProvider.currentState

    /** Latest captured visual context.  Null when none in the TTL window. */
    fun peekRecentVisualContext(): RecentVisualContext? = contextProvider.peekRecent()

    /** Friendly summary of the SDK's registration state (real backend
     *  only).  Empty string for stub / mock / disabled. */
    val registrationStatusLabel: String get() = deviceProvider.registrationStatusLabel

    /** Number of paired/reachable glasses the SDK currently sees.
     *  0 for stub / mock / disabled. */
    val visibleDeviceCount: Int get() = deviceProvider.visibleDeviceCount

    /** Link-state label for the first visible device (e.g.
     *  "CONNECTED" / "DISCONNECTED" / "CONNECTING").  Empty when no
     *  devices visible.  Distinct from [stateFlow] which describes
     *  the SESSION, not the BLE link. */
    val firstDeviceLinkLabel: String get() = deviceProvider.firstDeviceLinkLabel

    /** Last user-safe error message surfaced by the active backend. */
    val lastError: String? get() = deviceProvider.lastError

    /**
     * Trigger Meta AI's app-registration flow.  This is the prerequisite
     * for [connect] to succeed against real hardware — the SDK won't
     * surface any device until the user has explicitly approved Jarvis
     * via Meta AI.  Returns true when the registration intent was
     * launched; false when the active backend doesn't support it.
     */
    fun startRegistration(activity: android.app.Activity): Boolean =
        deviceProvider.startRegistration(activity)

    /**
     * Re-pick the provider after a settings change (e.g. "Use mock
     * Meta glasses" toggled).  Disconnects the old one first.
     */
    suspend fun refresh() {
        val old = providerImpl
        old.disconnect()
        providerImpl = pickProvider()
        Log.d(TAG, "[META_WEARABLES_INIT] " +
            "backend=${providerImpl::class.simpleName} state=${providerImpl.currentState}")
    }

    /**
     * Connect, opening a camera session if [withCamera] is true.
     *
     * The DAT SDK's `session.start()` is sync fire-and-forget — it
     * transitions to STARTING immediately and the real connection
     * completes asynchronously.  We therefore:
     *
     *   1. Kick the device-level connect (createSession + start).
     *   2. **Await** state ≥ CONNECTED (or terminal) with a bounded
     *      timeout.  Returning before that gives the user the
     *      false-negative they saw: state CONNECTING, caller reports
     *      "couldn't connect".
     *   3. Open the camera session if requested.
     *   4. **Await** state CAMERA_READY / STREAMING (still bounded).
     *   5. Return true iff the final state is capture-ready.
     */
    suspend fun connect(withCamera: Boolean = true): Boolean {
        val s = settingsProvider()
        if (!s.enabled) {
            Log.d(TAG, "[META_WEARABLES_DISABLED] state=$currentState")
            return false
        }
        val kicked = deviceProvider.connect()
        if (!kicked) {
            Log.d(TAG, "[META_WEARABLES_CONNECT_REJECTED] state=$currentState")
            return false
        }
        // Wait for the device to actually finish connecting.  CONNECTED
        // (= DeviceSessionState.STARTED) is the target; CONNECTING (=
        // STARTING/PAUSED) is in-flight; DISCONNECTED / ERROR / DISABLED
        // / SDK_UNAVAILABLE are terminal failures.
        val connectedOk = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            deviceProvider.stateFlow.first { state ->
                state == MetaWearablesState.CONNECTED ||
                    state == MetaWearablesState.CAMERA_READY ||
                    state == MetaWearablesState.STREAMING ||
                    state == MetaWearablesState.DISCONNECTED ||
                    state == MetaWearablesState.ERROR ||
                    state == MetaWearablesState.DISABLED ||
                    state == MetaWearablesState.SDK_UNAVAILABLE
            }.let { final ->
                final == MetaWearablesState.CONNECTED ||
                    final == MetaWearablesState.CAMERA_READY ||
                    final == MetaWearablesState.STREAMING
            }
        } == true

        if (!connectedOk) {
            Log.d(TAG, "[META_WEARABLES_CONNECT_TIMEOUT_OR_FAIL] state=$currentState")
            return false
        }
        if (!withCamera) return true

        // Camera session: same wait pattern.  startCameraSession is
        // itself synchronous-ish (returns once addStream + stream.start
        // have been issued), but the stream's STARTING → STARTED →
        // STREAMING progression is async.  CAMERA_READY = the camera
        // is usable for capture; STREAMING = also fine.
        cameraProvider.startCameraSession()
        val ready = withTimeoutOrNull(CAMERA_TIMEOUT_MS) {
            deviceProvider.stateFlow.first { state ->
                state.isReadyForCapture ||
                    state == MetaWearablesState.DISCONNECTED ||
                    state == MetaWearablesState.ERROR
            }.isReadyForCapture
        } == true
        if (!ready) {
            Log.d(TAG, "[META_WEARABLES_CAMERA_TIMEOUT_OR_FAIL] state=$currentState")
            return false
        }
        return true
    }

    suspend fun disconnect() = deviceProvider.disconnect()

    /**
     * Try to capture a single photo from the glasses.  Returns the
     * URI on success, null on failure (state machine left at a
     * sensible resting state by the provider).
     *
     * Caller (e.g. `LookAtThisWearableTool`) decides what to do on
     * null — typically fall back to the phone camera if the user has
     * enabled that.
     */
    suspend fun capturePhoto(): String? {
        if (!settingsProvider().enabled) return null
        // Auto-connect path: if we're idle, try to bring the device up.
        if (currentState == MetaWearablesState.DISCONNECTED ||
            currentState == MetaWearablesState.CONNECTED) {
            connect(withCamera = true)
        }
        if (!currentState.isReadyForCapture &&
            currentState != MetaWearablesState.CONNECTED) {
            Log.d(TAG, "[META_CAMERA_UNAVAILABLE] state=$currentState")
            return null
        }
        return cameraProvider.capturePhoto()
    }

    /** Stop any streaming / camera session.  Safe to call from any state. */
    suspend fun stopCamera() = cameraProvider.stopCameraSession()

    // ── Provider selection ─────────────────────────────────────────────────

    private fun pickProvider(): WearableDeviceProvider {
        val s = settingsProvider()
        if (!s.enabled) return DisabledProvider
        if (s.useMockDevice) return MockMetaWearablesProvider()
        if (sdkPresent()) {
            // Real Meta DAT SDK is on the classpath — instantiate the
            // production provider reflectively.  We can't reference
            // RealMetaWearablesProvider directly because that file
            // lives in a conditional source set (src/mwdat/java) that
            // is only included when `github_token` is configured in
            // local.properties.  Going through Class.forName lets the
            // manager compile cleanly regardless of token state.
            return try {
                val cls = Class.forName(
                    "com.jarvis.assistant.wearables.meta.RealMetaWearablesProvider",
                    true,
                    this::class.java.classLoader,
                )
                @Suppress("UNCHECKED_CAST")
                cls.getDeclaredConstructor(Context::class.java)
                    .newInstance(context) as WearableDeviceProvider
            } catch (t: Throwable) {
                Log.w(TAG, "[META_WEARABLES_ERROR] " +
                    "RealMetaWearablesProvider init threw — using stub: ${t.message}")
                StubMetaWearablesProvider()
            }
        }
        return StubMetaWearablesProvider()
    }

    /**
     * Probe for the DAT SDK at runtime.  We try a small set of
     * plausible FQ names because the layout shifted between artifact
     * package (`mwdat-*`) and Java package (`com.meta.wearable.dat.*`)
     * — and we don't want a wrong guess to silently downgrade every
     * user to the stub.  Logs the first hit so a single logcat line
     * tells the developer which path actually exists in their APK.
     */
    private fun sdkPresent(): Boolean {
        val candidates = listOf(
            // Confirmed FQ name from a dex dump of the 0.7.0 APK.
            // The docs site's "objects / selectors / session / types"
            // groupings are UI categorisations, not real packages —
            // the actual root is `com.meta.wearable.dat.core`.
            "com.meta.wearable.dat.core.Wearables",
            // Defensive fallbacks in case Meta restructures in a
            // future point release.  Order: most-likely first.
            "com.meta.wearable.dat.objects.Wearables",
            "com.meta.wearable.dat.Wearables",
            "com.meta.wearable.mwdat.core.Wearables",
            "com.meta.wearable.mwdat.objects.Wearables",
        )
        val cl = this::class.java.classLoader
        for (name in candidates) {
            try {
                Class.forName(name, false, cl)
                Log.d(TAG, "[META_WEARABLES_SDK_PROBE] hit class=$name")
                return true
            } catch (_: ClassNotFoundException) {
                Log.v(TAG, "[META_WEARABLES_SDK_PROBE] miss class=$name")
            } catch (t: Throwable) {
                Log.w(TAG, "[META_WEARABLES_SDK_PROBE] probe threw for $name: ${t.message}")
            }
        }
        Log.w(TAG, "[META_WEARABLES_SDK_PROBE] no candidate resolved — falling back to stub")
        return false
    }

    /** Pseudo-provider used when the master toggle is OFF. */
    private object DisabledProvider :
        WearableDeviceProvider, WearableCameraProvider, WearableContextProvider {

        private val _state = MutableStateFlow(MetaWearablesState.DISABLED)
        override val stateFlow: StateFlow<MetaWearablesState> = _state.asStateFlow()
        override val currentState: MetaWearablesState get() = _state.value
        override val deviceName: String? = null
        override val batteryPercent: Int? = null
        override val lastError: String? = null
        override suspend fun connect()                = false
        override suspend fun disconnect()             = Unit
        override suspend fun startCameraSession()     = false
        override suspend fun stopCameraSession()      = Unit
        override suspend fun capturePhoto(): String?  = null
        override suspend fun startStream(onFrame: (ByteArray, Int, Int) -> Boolean) = false
        override suspend fun stopStream()             = Unit
        override fun peekRecent(): RecentVisualContext? = null
        override fun clearRecent()                    = Unit
    }

    companion object {
        private const val TAG = "MetaWearablesMgr"
        /** Max time to wait for the device session to reach CONNECTED. */
        private const val CONNECT_TIMEOUT_MS = 15_000L
        /** Max time to wait for the camera session to reach CAMERA_READY. */
        private const val CAMERA_TIMEOUT_MS  = 10_000L
    }
}
