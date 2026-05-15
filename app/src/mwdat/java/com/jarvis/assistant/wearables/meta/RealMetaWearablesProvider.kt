package com.jarvis.assistant.wearables.meta

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.meta.wearable.mwdat.camera.CaptureError
import com.meta.wearable.mwdat.camera.PhotoData
import com.meta.wearable.mwdat.camera.Stream
import com.meta.wearable.mwdat.camera.StreamConfiguration
import com.meta.wearable.mwdat.camera.StreamError
import com.meta.wearable.mwdat.camera.StreamState
import com.meta.wearable.mwdat.camera.VideoFrame
import com.meta.wearable.mwdat.camera.VideoQuality
import com.meta.wearable.mwdat.objects.Wearables
import com.meta.wearable.mwdat.selectors.AutoDeviceSelector
import com.meta.wearable.mwdat.session.DeviceSession
import com.meta.wearable.mwdat.session.DeviceSessionError
import com.meta.wearable.mwdat.session.DeviceSessionState
import com.meta.wearable.mwdat.types.DatResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * RealMetaWearablesProvider — production backend for the Meta DAT
 * SDK (mwdat-core + mwdat-camera v0.7).  Maps the SDK surface onto
 * our [WearableDeviceProvider] + [WearableCameraProvider] +
 * [WearableContextProvider] contract so nothing else in Jarvis
 * cares whether the backend is stub / mock / real.
 *
 * **Lifecycle wiring:**
 *
 *   connect()             Wearables.createSession(AutoDeviceSelector())
 *                         .getOrElse { ... } ; session.start()
 *                         → DeviceSessionState observed → MetaWearablesState
 *   disconnect()          session.stop()
 *   startCameraSession()  session.addStream(StreamConfiguration())
 *                         .getOrElse { ... } ; stream.start() ; wait STREAMING
 *                         → CAMERA_READY
 *   capturePhoto()        stream.capturePhoto() (requires STREAMING)
 *                         → write PhotoData.HEIC / .Bitmap to filesDir/pictures
 *                         → return absolute path
 *   startStream(onFrame)  stream.videoStream.collect → onFrame(buffer, w, h)
 *   stopCameraSession()   session.removeStream()
 *
 * **Errors:** DeviceSessionError + StreamError + CaptureError all map
 * to friendly state transitions / lastError messages.  The provider
 * never throws to its caller.
 *
 * Files: photos land in `filesDir/pictures/glasses_photo_<ts>.{jpg,heic}`
 * — same directory the existing camera tool uses, so the FileProvider
 * paths already cover them (jarvis_pictures path entry, see
 * res/xml/jarvis_file_paths.xml).
 */
class RealMetaWearablesProvider(
    private val context: Context,
) : WearableDeviceProvider, WearableCameraProvider, WearableContextProvider {

    private val _stateFlow = MutableStateFlow(MetaWearablesState.DISCONNECTED)
    override val stateFlow: StateFlow<MetaWearablesState> = _stateFlow.asStateFlow()
    override val currentState: MetaWearablesState get() = _stateFlow.value

    @Volatile override var deviceName: String? = null;       private set
    @Volatile override var batteryPercent: Int? = null;      private set
    @Volatile override var lastError: String? = null;        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val recent = AtomicReference<RecentVisualContext?>(null)

    @Volatile private var session: DeviceSession? = null
    @Volatile private var stream: Stream? = null
    @Volatile private var sessionStateJob: Job? = null
    @Volatile private var sessionErrorsJob: Job? = null
    @Volatile private var streamStateJob: Job? = null
    @Volatile private var streamErrorsJob: Job? = null
    @Volatile private var streamFramesJob: Job? = null

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

        sessionStateJob?.cancel()
        sessionStateJob = scope.launch {
            newSession.state.collect { remote ->
                val mapped = mapSessionState(remote)
                Log.d(TAG, "[META_WEARABLES_SESSION_STATE] $remote → $mapped")
                val keepCameraStates = mapped == MetaWearablesState.CONNECTED &&
                    _stateFlow.value in setOf(
                        MetaWearablesState.CAMERA_READY,
                        MetaWearablesState.STREAMING,
                        MetaWearablesState.CAPTURING,
                    )
                if (!keepCameraStates) _stateFlow.value = mapped
            }
        }

        sessionErrorsJob?.cancel()
        sessionErrorsJob = scope.launch {
            newSession.errors.collect { err ->
                Log.w(TAG, "[META_WEARABLES_ERROR] session error: $err")
                lastError = err.toString()
                if (err == DeviceSessionError.DEVICE_DISCONNECTED ||
                    err == DeviceSessionError.DEVICE_POWERED_OFF
                ) {
                    _stateFlow.value = MetaWearablesState.DISCONNECTED
                }
            }
        }

        newSession.start()
        return true
    }

    override suspend fun disconnect() = mutex.withLock {
        val s = session ?: return
        Log.d(TAG, "[META_WEARABLES_DISCONNECTED] backend=real")
        streamFramesJob?.cancel(); streamFramesJob = null
        streamStateJob?.cancel();  streamStateJob  = null
        streamErrorsJob?.cancel(); streamErrorsJob = null
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
        if (_stateFlow.value !in setOf(
                MetaWearablesState.CONNECTED,
                MetaWearablesState.CAMERA_READY,
                MetaWearablesState.STREAMING,
            )) return false

        // 1. Register the stream capability.
        val newStream: Stream = when (val res = s.addStream(StreamConfiguration(
            videoQuality   = VideoQuality.MEDIUM,
            frameRate      = 24,
            compressVideo  = false,        // we want decoded YUV → Bitmap-friendly
        ))) {
            is DatResult.Success -> res.value
            is DatResult.Failure -> {
                Log.w(TAG, "[META_WEARABLES_ERROR] addStream failed: ${res.error}")
                lastError = res.error.toString()
                return false
            }
        }
        stream = newStream

        // 2. Activate the camera.  The SDK requires both addStream() AND
        //    Stream.start() — only then does the device wake up.
        when (val r = newStream.start()) {
            is DatResult.Success -> Unit
            is DatResult.Failure -> {
                Log.w(TAG, "[META_WEARABLES_ERROR] stream.start failed: ${r.error}")
                lastError = r.error.toString()
                stream = null
                s.removeStream()
                return false
            }
        }

        // 3. Observe the stream's own state machine.  StreamState
        //    STARTING → STARTED → STREAMING is the path to camera-ready;
        //    STOPPING / STOPPED / CLOSED drop us back to CONNECTED.
        streamStateJob?.cancel()
        streamStateJob = scope.launch {
            newStream.state.collect { ss ->
                Log.d(TAG, "[META_CAMERA_STREAM_STATE] $ss")
                _stateFlow.value = when (ss) {
                    StreamState.STARTING, StreamState.STARTED -> MetaWearablesState.CAMERA_READY
                    StreamState.STREAMING                     -> MetaWearablesState.CAMERA_READY
                    StreamState.STOPPING, StreamState.STOPPED,
                    StreamState.CLOSED                        -> MetaWearablesState.CONNECTED
                }
            }
        }

        streamErrorsJob?.cancel()
        streamErrorsJob = scope.launch {
            newStream.errorStream.collect { err ->
                Log.w(TAG, "[META_WEARABLES_ERROR] stream error: $err")
                lastError = err.description
                if (err == StreamError.CRITICAL_STREAM_ERROR ||
                    err == StreamError.HINGE_CLOSED ||
                    err == StreamError.PERMISSIONS_DENIED ||
                    err == StreamError.THERMAL_EMERGENCY
                ) {
                    streamFramesJob?.cancel(); streamFramesJob = null
                    _stateFlow.value = MetaWearablesState.ERROR
                }
            }
        }

        Log.d(TAG, "[META_CAMERA_READY] backend=real (waiting for STREAMING for capture)")
        return true
    }

    override suspend fun stopCameraSession() = mutex.withLock {
        val s = session ?: return
        streamFramesJob?.cancel(); streamFramesJob = null
        streamStateJob?.cancel();  streamStateJob  = null
        streamErrorsJob?.cancel(); streamErrorsJob = null
        stream?.stop()
        stream = null
        s.removeStream()
        if (_stateFlow.value in setOf(
                MetaWearablesState.STREAMING,
                MetaWearablesState.CAMERA_READY,
                MetaWearablesState.CAPTURING,
            )) {
            _stateFlow.value = MetaWearablesState.CONNECTED
        }
    }

    override suspend fun capturePhoto(): String? {
        val s = stream
        if (s == null) {
            Log.w(TAG, "[META_CAMERA_UNAVAILABLE] no active stream")
            return null
        }
        // Capture only works while STREAMING — wait up to 5 s for that
        // state.  This handles the "user said 'take a glasses photo'
        // immediately after connect" case where the stream is still
        // STARTING.
        val streaming = withTimeoutOrNull(5_000L) {
            s.state.first { it == StreamState.STREAMING }
            true
        } == true
        if (!streaming) {
            Log.w(TAG, "[META_CAMERA_UNAVAILABLE] stream never reached STREAMING " +
                "(currentState=${s.state.value})")
            lastError = "Stream didn't reach STREAMING in time"
            return null
        }

        val prior = _stateFlow.value
        _stateFlow.value = MetaWearablesState.CAPTURING
        try {
            val result = s.capturePhoto()
            return when (result) {
                is DatResult.Success -> {
                    val path = savePhoto(result.value)
                    if (path != null) {
                        recent.set(
                            RecentVisualContext(
                                source      = RecentVisualContext.Source.META_GLASSES,
                                mediaType   = RecentVisualContext.MediaType.PHOTO,
                                uri         = path,
                                timestampMs = System.currentTimeMillis(),
                            )
                        )
                        Log.d(TAG, "[META_CAMERA_PHOTO_CAPTURED] backend=real path=$path")
                    } else {
                        Log.w(TAG, "[META_WEARABLES_ERROR] photo received but save failed")
                    }
                    path
                }
                is DatResult.Failure -> {
                    val e = result.error
                    val desc = e.description
                    Log.w(TAG, "[META_WEARABLES_ERROR] capturePhoto failed: $desc")
                    lastError = desc
                    // CaptureError.NotStreaming shouldn't fire after our
                    // first {} wait above, but if it does we surface it.
                    null
                }
            }
        } finally {
            // Don't smash CAPTURING back to a state lower than it actually
            // is — read it back from the stream's own state machine.
            _stateFlow.value = when (s.state.value) {
                StreamState.STREAMING,
                StreamState.STARTED, StreamState.STARTING -> MetaWearablesState.CAMERA_READY
                else -> MetaWearablesState.CONNECTED
            }
        }
    }

    override suspend fun startStream(
        onFrame: (frameBytes: ByteArray, widthPx: Int, heightPx: Int) -> Boolean,
    ): Boolean {
        val s = stream ?: return false
        if (s.state.value != StreamState.STREAMING) {
            // Wait briefly for STREAMING — same rationale as capturePhoto.
            withTimeoutOrNull(5_000L) {
                s.state.first { it == StreamState.STREAMING }
            } ?: run {
                Log.w(TAG, "[META_CAMERA_UNAVAILABLE] stream not STREAMING")
                return false
            }
        }
        _stateFlow.value = MetaWearablesState.STREAMING
        Log.d(TAG, "[META_CAMERA_STREAM_START] backend=real")
        streamFramesJob?.cancel()
        streamFramesJob = scope.launch {
            try {
                s.videoStream.collect { frame: VideoFrame ->
                    if (frame.isCodecConfig) return@collect  // skip VPS/SPS/PPS
                    val bytes = ByteArray(frame.buffer.remaining())
                    frame.buffer.duplicate().get(bytes)
                    Log.d(TAG, "[META_CAMERA_FRAME] backend=real " +
                        "w=${frame.width} h=${frame.height} bytes=${bytes.size}")
                    val keep = try {
                        onFrame(bytes, frame.width, frame.height)
                    } catch (t: Throwable) {
                        Log.w(TAG, "onFrame threw — stopping stream", t); false
                    }
                    if (!keep) this@launch.coroutineContext[Job]?.cancel()
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
        streamFramesJob?.cancel()
        streamFramesJob = null
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

    /**
     * Persist [photo] to `filesDir/pictures/glasses_photo_<ts>.{ext}`.
     * Returns the absolute path or null on I/O failure.
     *
     * - `PhotoData.HEIC` → write the HEIC ByteBuffer verbatim → `.heic`
     * - `PhotoData.Bitmap` → JPEG-compress at 90% → `.jpg`
     *
     * The pictures dir is already covered by `jarvis_pictures` in
     * `jarvis_file_paths.xml`, so ViewMediaTool / ShareMediaTool can
     * hand the resulting URI to Gallery / Photos via FileProvider.
     */
    private fun savePhoto(photo: PhotoData): String? {
        val dir = File(context.filesDir, "pictures").apply { if (!exists()) mkdirs() }
        val ts = System.currentTimeMillis()
        return try {
            when (photo) {
                is PhotoData.HEIC -> {
                    val f = File(dir, "glasses_photo_$ts.heic")
                    FileOutputStream(f).channel.use { ch ->
                        ch.write(photo.data.duplicate())
                    }
                    f.absolutePath
                }
                is PhotoData.Bitmap -> {
                    val f = File(dir, "glasses_photo_$ts.jpg")
                    FileOutputStream(f).use { fos ->
                        photo.bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                    }
                    f.absolutePath
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "savePhoto failed", t); null
        }
    }

    companion object { private const val TAG = "MetaWearablesReal" }
}
