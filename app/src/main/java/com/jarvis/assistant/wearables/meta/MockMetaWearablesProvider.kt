package com.jarvis.assistant.wearables.meta

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/**
 * MockMetaWearablesProvider — fake glasses, pure in-memory.
 *
 * Used by:
 *   - The "Use mock Meta glasses" Settings toggle in dev / debug
 *     builds so a developer without hardware can exercise the
 *     entire glasses flow.
 *   - Unit tests that need a deterministic state machine without
 *     the real SDK on the classpath.
 *
 * The mock honours the same [WearableDeviceProvider] +
 * [WearableCameraProvider] + [WearableContextProvider] contracts as
 * the real backend.  Frames are a single 1×1 white pixel; photo
 * captures produce a synthetic content URI string.
 *
 * **Failure modes** the mock simulates on demand (Settings →
 * Diagnostics):
 *   - [simulateDisconnect] — drops to DISCONNECTED mid-stream.
 *   - [simulatePermissionMissing] — pins state at PERMISSION_MISSING
 *     until the developer toggles it off.
 *   - [simulateError] — pins state at ERROR.
 */
class MockMetaWearablesProvider(
    private val clock: () -> Long = System::currentTimeMillis,
    /** Delay (ms) to inject in async operations so the UI shows
     *  realistic transient states.  0 = instant for tests. */
    private val syntheticLatencyMs: Long = 0L,
) : WearableDeviceProvider, WearableCameraProvider, WearableContextProvider {

    private val _stateFlow = MutableStateFlow(MetaWearablesState.DISCONNECTED)
    override val stateFlow: StateFlow<MetaWearablesState> = _stateFlow.asStateFlow()
    override val currentState: MetaWearablesState get() = _stateFlow.value

    override var deviceName: String? = "Mock Meta Glasses"
        private set
    override var batteryPercent: Int? = 88
        private set
    override var lastError: String? = null
        private set

    private val mutex = Mutex()
    private val recentContext = AtomicReference<RecentVisualContext?>(null)
    @Volatile private var streamRunning = false
    @Volatile private var simulatedPermissionMissing = false
    @Volatile private var simulatedError = false

    override suspend fun connect(): Boolean = mutex.withLock {
        if (simulatedPermissionMissing) {
            _stateFlow.value = MetaWearablesState.PERMISSION_MISSING
            return false
        }
        if (simulatedError) {
            _stateFlow.value = MetaWearablesState.ERROR
            lastError = "Simulated mock error"
            return false
        }
        if (currentState in setOf(
                MetaWearablesState.CONNECTED,
                MetaWearablesState.CAMERA_READY,
                MetaWearablesState.STREAMING,
            )) return true
        Log.d(TAG, "[META_WEARABLES_CONNECT_START] backend=mock")
        _stateFlow.value = MetaWearablesState.CONNECTING
        if (syntheticLatencyMs > 0) delay(syntheticLatencyMs)
        _stateFlow.value = MetaWearablesState.CONNECTED
        Log.d(TAG, "[META_WEARABLES_CONNECTED] backend=mock name=$deviceName")
        return true
    }

    override suspend fun disconnect() = mutex.withLock {
        if (!currentState.canDisconnect && currentState != MetaWearablesState.CONNECTED) return
        Log.d(TAG, "[META_WEARABLES_DISCONNECTED] backend=mock")
        streamRunning = false
        _stateFlow.value = MetaWearablesState.DISCONNECTED
    }

    override suspend fun startCameraSession(): Boolean = mutex.withLock {
        if (currentState !in setOf(MetaWearablesState.CONNECTED, MetaWearablesState.CAMERA_READY))
            return false
        _stateFlow.value = MetaWearablesState.CAMERA_READY
        Log.d(TAG, "[META_CAMERA_READY] backend=mock")
        return true
    }

    override suspend fun stopCameraSession() = mutex.withLock {
        streamRunning = false
        if (currentState == MetaWearablesState.STREAMING ||
            currentState == MetaWearablesState.CAMERA_READY ||
            currentState == MetaWearablesState.CAPTURING
        ) {
            _stateFlow.value = MetaWearablesState.CONNECTED
        }
    }

    override suspend fun capturePhoto(): String? {
        if (currentState !in setOf(
                MetaWearablesState.CONNECTED,
                MetaWearablesState.CAMERA_READY,
                MetaWearablesState.STREAMING,
            )) return null
        val prior = currentState
        _stateFlow.value = MetaWearablesState.CAPTURING
        if (syntheticLatencyMs > 0) delay(syntheticLatencyMs)
        val uri = "content://com.jarvis.assistant.mock/wearable/photo_${clock()}.jpg"
        recentContext.set(
            RecentVisualContext(
                source     = RecentVisualContext.Source.MOCK_WEARABLE,
                mediaType  = RecentVisualContext.MediaType.PHOTO,
                uri        = uri,
                timestampMs= clock(),
            )
        )
        _stateFlow.value = prior.takeIf { it != MetaWearablesState.CAPTURING } ?: MetaWearablesState.CAMERA_READY
        Log.d(TAG, "[META_CAMERA_PHOTO_CAPTURED] backend=mock uri=$uri")
        return uri
    }

    override suspend fun startStream(
        onFrame: (ByteArray, Int, Int) -> Boolean,
    ): Boolean {
        if (currentState != MetaWearablesState.CAMERA_READY &&
            currentState != MetaWearablesState.CONNECTED) return false
        _stateFlow.value = MetaWearablesState.STREAMING
        streamRunning = true
        Log.d(TAG, "[META_CAMERA_STREAM_START] backend=mock")
        // Deliver one synthetic frame so callers see at least one
        // [META_CAMERA_FRAME] log without spinning a coroutine.
        val pixel = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        onFrame(pixel, 1, 1)
        Log.d(TAG, "[META_CAMERA_FRAME] backend=mock w=1 h=1")
        return true
    }

    override suspend fun stopStream() {
        streamRunning = false
        if (currentState == MetaWearablesState.STREAMING) {
            _stateFlow.value = MetaWearablesState.CAMERA_READY
        }
    }

    override fun peekRecent(): RecentVisualContext? =
        recentContext.get()?.takeUnless { it.isExpired(clock()) }

    override fun clearRecent() { recentContext.set(null) }

    override suspend fun simulateDisconnect() = disconnect()

    fun simulatePermissionMissing(value: Boolean) {
        simulatedPermissionMissing = value
        if (value) _stateFlow.value = MetaWearablesState.PERMISSION_MISSING
        else if (currentState == MetaWearablesState.PERMISSION_MISSING)
            _stateFlow.value = MetaWearablesState.DISCONNECTED
    }

    fun simulateError(value: Boolean) {
        simulatedError = value
        if (value) {
            lastError = "Simulated mock error"
            _stateFlow.value = MetaWearablesState.ERROR
        } else {
            lastError = null
            if (currentState == MetaWearablesState.ERROR)
                _stateFlow.value = MetaWearablesState.DISCONNECTED
        }
    }

    companion object { private const val TAG = "MetaWearablesMock" }
}
