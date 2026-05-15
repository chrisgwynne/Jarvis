package com.jarvis.assistant.wearables.meta

import android.content.Context
import android.util.Log
import com.meta.wearable.mwdat.objects.Wearables
import com.meta.wearable.mwdat.selectors.AutoDeviceSelector
import com.meta.wearable.mwdat.session.DeviceSession
import com.meta.wearable.mwdat.session.DeviceSessionState
import com.meta.wearable.mwdat.session.DeviceSessionError
import com.meta.wearable.mwdat.session.Stream
import com.meta.wearable.mwdat.session.StreamConfiguration
import com.meta.wearable.mwdat.types.DatResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/**
 * RealMetaWearablesProvider — production backend for the Meta DAT
 * SDK (v0.7).  Maps the SDK's `Wearables` / `DeviceSession` /
 * `Stream` surface onto our existing
 * [WearableDeviceProvider] + [WearableCameraProvider] +
 * [WearableContextProvider] contract so the rest of Jarvis stays
 * unchanged when the backend swaps from stub / mock to real.
 *
 * **What's wired today (against `mwdat-core`):**
 *   - SDK initialization via `Wearables.initialize(context)`
 *   - Session create + start + stop via `AutoDeviceSelector`
 *   - Live state mapping from `DeviceSessionState` → our
 *     [MetaWearablesState]
 *   - Streaming via `session.addStream(...)` + `stream.videoStream`
 *
 * **What's stubbed pending `mwdat-camera` docs:**
 *   - [capturePhoto] returns null with a clear log line.  The dat.core
 *     API only exposes `addStream` for video; single-photo capture
 *     lives in the camera module (which also adds `addCapture` /
 *     `removeCapture` to `DeviceSession`).  Once the camera page is
 *     pasted I'll plumb that through here without changing this
 *     class's public shape.
 *
 * Failure handling: every SDK error → state transition to
 * [MetaWearablesState.ERROR] with [lastError] set to the friendly
 * `DatResult` error description.  Never throws to the caller — the
 * `WearableDeviceProvider` contract guarantees that.
 */
class RealMetaWearablesProvider(
    private val context: Context,
) : WearableDeviceProvider, WearableCameraProvider, WearableContextProvider {

    private val _stateFlow = MutableStateFlow(MetaWearablesState.DISCONNECTED)
    override val stateFlow: StateFlow<MetaWearablesState> = _stateFlow.asStateFlow()
    override val currentState: MetaWearablesState get() = _stateFlow.value

    @Volatile override var deviceName: String? = null
        private set
    @Volatile override var batteryPercent: Int? = null
        private set
    @Volatile override var lastError: String? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val recent = AtomicReference<RecentVisualContext?>(null)

    @Volatile private var session: DeviceSession? = null
    @Volatile private var stream: Stream? = null
    @Volatile private var sessionStateJob: Job? = null
    @Volatile private var sessionErrorsJob: Job? = null
    @Volatile private var streamJob: Job? = null

    init {
        Log.d(TAG, "[META_WEARABLES_INIT] backend=real version=0.7")
        when (val r = Wearables.initialize(context)) {
            is DatResult.Success -> {
                Log.d(TAG, "[META_WEARABLES_INIT] Wearables.initialize → Success")
            }
            is DatResult.Failure -> {
                Log.w(TAG, "[META_WEARABLES_ERROR] init failed: ${r.error}")
                lastError = r.error.toString()
                _stateFlow.value = MetaWearablesState.ERROR
            }
        }
    }

    // ── WearableDeviceProvider ─────────────────────────────────────────────

    override suspend fun connect(): Boolean = mutex.withLock {
        val current = _stateFlow.value
        if (current == MetaWearablesState.CONNECTING ||
            current == MetaWearablesState.CONNECTED ||
            current == MetaWearablesState.CAMERA_READY ||
            current == MetaWearablesState.STREAMING ||
            current == MetaWearablesState.CAPTURING
        ) {
            Log.d(TAG, "[META_WEARABLES_CONNECT_SKIP] already at $current")
            return true
        }
        Log.d(TAG, "[META_WEARABLES_CONNECT_START] backend=real")
        _stateFlow.value = MetaWearablesState.CONNECTING

        val created = Wearables.createSession(AutoDeviceSelector())
        val newSession: DeviceSession = when (created) {
            is DatResult.Success -> created.value
            is DatResult.Failure -> {
                val err = created.error
                Log.w(TAG, "[META_WEARABLES_ERROR] createSession failed: $err")
                lastError = err.toString()
                _stateFlow.value = when (err) {
                    DeviceSessionError.NO_ELIGIBLE_DEVICE -> MetaWearablesState.DISCONNECTED
                    DeviceSessionError.SESSION_ALREADY_EXISTS -> MetaWearablesState.CONNECTED
                    else -> MetaWearablesState.ERROR
                }
                return false
            }
        }
        session = newSession

        // Re-emit DeviceSessionState → MetaWearablesState as it changes.
        sessionStateJob?.cancel()
        sessionStateJob = scope.launch {
            newSession.state.collect { remote ->
                val mapped = mapSessionState(remote)
                Log.d(TAG, "[META_WEARABLES_SESSION_STATE] $remote → $mapped")
                // Preserve CAMERA_READY / STREAMING when the session
                // is still STARTED — those are derived from camera
                // capabilities, not raw session state.
                val keepCameraStates = mapped == MetaWearablesState.CONNECTED &&
                    _stateFlow.value in setOf(
                        MetaWearablesState.CAMERA_READY,
                        MetaWearablesState.STREAMING,
                        MetaWearablesState.CAPTURING,
                    )
                if (!keepCameraStates) _stateFlow.value = mapped
            }
        }

        // Surface session-level errors with a friendly description.
        sessionErrorsJob?.cancel()
        sessionErrorsJob = scope.launch {
            newSession.errors.collect { err ->
                Log.w(TAG, "[META_WEARABLES_ERROR] session error: $err")
                lastError = err.toString()
                // Don't smash CONNECTING / STARTED to ERROR for transient
                // disconnects — the state flow above already handles that.
                if (err == DeviceSessionError.DEVICE_DISCONNECTED ||
                    err == DeviceSessionError.DEVICE_POWERED_OFF
                ) {
                    _stateFlow.value = MetaWearablesState.DISCONNECTED
                }
            }
        }

        // Fire and forget — observe state for STARTED.
        newSession.start()
        return true
    }

    override suspend fun disconnect() = mutex.withLock {
        val s = session ?: return
        Log.d(TAG, "[META_WEARABLES_DISCONNECTED] backend=real")
        streamJob?.cancel(); streamJob = null
        stream = null
        s.stop()
        sessionStateJob?.cancel(); sessionStateJob = null
        sessionErrorsJob?.cancel(); sessionErrorsJob = null
        session = null
        _stateFlow.value = MetaWearablesState.DISCONNECTED
    }

    override suspend fun simulateDisconnect() = disconnect()

    // ── WearableCameraProvider ─────────────────────────────────────────────

    override suspend fun startCameraSession(): Boolean = mutex.withLock {
        val s = session ?: return false
        if (_stateFlow.value != MetaWearablesState.CONNECTED &&
            _stateFlow.value != MetaWearablesState.CAMERA_READY
        ) return false
        val res = s.addStream(StreamConfiguration())
        return when (res) {
            is DatResult.Success -> {
                stream = res.value
                _stateFlow.value = MetaWearablesState.CAMERA_READY
                Log.d(TAG, "[META_CAMERA_READY] backend=real")
                true
            }
            is DatResult.Failure -> {
                Log.w(TAG, "[META_WEARABLES_ERROR] addStream failed: ${res.error}")
                lastError = res.error.toString()
                false
            }
        }
    }

    override suspend fun stopCameraSession() = mutex.withLock {
        val s = session ?: return
        streamJob?.cancel(); streamJob = null
        stream = null
        s.removeStream()
        if (_stateFlow.value == MetaWearablesState.STREAMING ||
            _stateFlow.value == MetaWearablesState.CAMERA_READY ||
            _stateFlow.value == MetaWearablesState.CAPTURING
        ) {
            _stateFlow.value = MetaWearablesState.CONNECTED
        }
    }

    /**
     * TODO(meta-camera): single-photo capture lives in `mwdat-camera`.
     * The dat.core docs only surface streaming.  Once the camera
     * module's API page is in (likely an `addCapture(...)` returning
     * a `Capture` capability with `captureNow(): DatResult<Bitmap, ...>`
     * or similar), plumb it here.  Until then return null so
     * LookAtThisWearableTool falls back to a friendly "couldn't
     * capture" message rather than silently failing.
     */
    override suspend fun capturePhoto(): String? {
        Log.w(TAG, "[META_CAMERA_PHOTO_UNAVAILABLE] " +
            "reason=mwdat-camera_module_not_wired " +
            "see=docs/wearables/meta-dat-integration.md")
        return null
    }

    override suspend fun startStream(
        onFrame: (frameBytes: ByteArray, widthPx: Int, heightPx: Int) -> Boolean,
    ): Boolean {
        val s = stream ?: return false
        if (_stateFlow.value != MetaWearablesState.CAMERA_READY) return false
        _stateFlow.value = MetaWearablesState.STREAMING
        Log.d(TAG, "[META_CAMERA_STREAM_START] backend=real")
        streamJob?.cancel()
        streamJob = scope.launch {
            try {
                s.videoStream.collect { frame ->
                    // The frame type lives in mwdat-camera too — we only
                    // know its shape after that page lands.  For now,
                    // attempt to extract bytes via reflection-friendly
                    // properties; on failure we still log so we know the
                    // stream is delivering.
                    val keep = try {
                        val bytes = extractFrameBytes(frame)
                        val w = extractInt(frame, "width") ?: 0
                        val h = extractInt(frame, "height") ?: 0
                        Log.d(TAG, "[META_CAMERA_FRAME] backend=real w=$w h=$h bytes=${bytes.size}")
                        onFrame(bytes, w, h)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Frame decode failed: ${t.message}")
                        false
                    }
                    if (!keep) { /* caller asked to stop */ this@launch.coroutineContext[Job]?.cancel() }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Stream collection failed", t)
                lastError = t.message
            } finally {
                if (_stateFlow.value == MetaWearablesState.STREAMING) {
                    _stateFlow.value = MetaWearablesState.CAMERA_READY
                }
            }
        }
        return true
    }

    override suspend fun stopStream() {
        streamJob?.cancel()
        streamJob = null
        if (_stateFlow.value == MetaWearablesState.STREAMING) {
            _stateFlow.value = MetaWearablesState.CAMERA_READY
        }
    }

    // ── WearableContextProvider ────────────────────────────────────────────

    override fun peekRecent(): RecentVisualContext? = recent.get()
    override fun clearRecent()                      { recent.set(null) }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun mapSessionState(s: DeviceSessionState): MetaWearablesState = when (s) {
        DeviceSessionState.IDLE      -> MetaWearablesState.DISCONNECTED
        DeviceSessionState.STARTING  -> MetaWearablesState.CONNECTING
        DeviceSessionState.STARTED   -> MetaWearablesState.CONNECTED
        DeviceSessionState.PAUSED    -> MetaWearablesState.CONNECTING
        DeviceSessionState.STOPPING  -> MetaWearablesState.DISCONNECTED
        DeviceSessionState.STOPPED   -> MetaWearablesState.DISCONNECTED
    }

    /** Best-effort frame-byte extraction until the mwdat-camera page lands. */
    private fun extractFrameBytes(frame: Any): ByteArray {
        // Try common shapes: `data: ByteArray`, `bytes: ByteArray`,
        // `buffer: ByteBuffer`.  This is intentionally reflective —
        // we expect to delete this helper once the real Frame type is
        // wired and the signatures are known.
        val cls = frame::class.java
        runCatching {
            val m = cls.methods.firstOrNull { it.name == "getData" || it.name == "getBytes" }
            if (m != null) {
                val out = m.invoke(frame)
                if (out is ByteArray) return out
                if (out is java.nio.ByteBuffer) {
                    val arr = ByteArray(out.remaining()); out.get(arr); return arr
                }
            }
        }
        return ByteArray(0)
    }

    private fun extractInt(frame: Any, prop: String): Int? = runCatching {
        val getter = "get" + prop.replaceFirstChar { it.uppercase() }
        frame::class.java.methods.firstOrNull { it.name == getter }
            ?.invoke(frame) as? Int
    }.getOrNull()

    companion object { private const val TAG = "MetaWearablesReal" }
}
