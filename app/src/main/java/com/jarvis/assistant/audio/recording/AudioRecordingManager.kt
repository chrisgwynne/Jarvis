package com.jarvis.assistant.audio.recording

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/** Current state of the recording session. */
enum class RecordingState { IDLE, RECORDING, ERROR }

/** Lightweight snapshot of an in-progress or completed recording session. */
data class RecordingSession(
    val file: File,
    val startedAt: Long = System.currentTimeMillis()
)

/** Typed results from AudioRecordingManager operations. */
sealed class RecordingResult {
    data class Started(val session: RecordingSession)            : RecordingResult()
    data class Stopped(val file: File, val durationMs: Long)     : RecordingResult()
    data class AlreadyRecording(val session: RecordingSession)   : RecordingResult()
    object NotRecording                                          : RecordingResult()
    data class Failure(val reason: String)                       : RecordingResult()
}

/**
 * AudioRecordingManager — single MediaRecorder session at a time.
 *
 * STATE MACHINE:
 *   IDLE → start()  → RECORDING
 *   RECORDING → stop() → IDLE  (file written)
 *   RECORDING → start() → AlreadyRecording  (no change)
 *   IDLE → stop() → NotRecording  (no change)
 *   Any exception → resources released, back to IDLE
 *
 * FORMAT:
 *   MPEG-4 container / AAC encoder — compact, high-quality, accepted by Whisper API.
 *
 * MIC SHARING (Android 10+):
 *   Concurrent microphone capture is supported on API 29+. On API 26-28, starting
 *   a recording while the wake-word detector holds AudioRecord may fail. The failure
 *   is caught and returned as RecordingResult.Failure so the caller can inform the user.
 *
 * THREAD SAFETY:
 *   start() and stop() are @Synchronized. Callers run them on Dispatchers.IO.
 */
class AudioRecordingManager(val context: Context) {

    companion object {
        private const val TAG            = "AudioRecordingManager"
        private const val SAMPLE_RATE_HZ = 44_100
        private const val BIT_RATE_BPS   = 128_000
    }

    @Volatile
    var state: RecordingState = RecordingState.IDLE
        private set

    private var recorder: MediaRecorder? = null
    private var activeSession: RecordingSession? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start ambient microphone recording.
     * Returns [RecordingResult.AlreadyRecording] if a session is in progress.
     */
    @Synchronized
    fun start(): RecordingResult {
        if (state == RecordingState.RECORDING) {
            Log.d(TAG, "Already recording — returning AlreadyRecording")
            val session = activeSession
            if (session != null) return RecordingResult.AlreadyRecording(session)
            // Inconsistent state — RECORDING with no active session.  Reset and
            // fall through so the caller gets a fresh recording rather than an NPE.
            Log.w(TAG, "RECORDING state with null activeSession — resetting to IDLE")
            state = RecordingState.IDLE
        }

        val file = RecordingFileStore.createRecordingFile(context)
        Log.d(TAG, "Starting recording → ${file.name}")

        return try {
            recorder = buildRecorder(file).also {
                it.prepare()
                it.start()
            }
            val session = RecordingSession(file)
            activeSession = session
            state = RecordingState.RECORDING
            Log.d(TAG, "Recording started")
            RecordingResult.Started(session)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            releaseRecorder()
            state = RecordingState.IDLE
            RecordingResult.Failure("Recording failed to start")
        }
    }

    /**
     * Stop the active recording and finalize the file.
     * Returns [RecordingResult.NotRecording] if nothing is in progress.
     * Even if stop() throws, the file reference is returned so callers can inspect it.
     */
    @Synchronized
    fun stop(): RecordingResult {
        if (state != RecordingState.RECORDING) {
            Log.d(TAG, "Not recording — returning NotRecording")
            return RecordingResult.NotRecording
        }

        val session = activeSession!!
        val durationMs = System.currentTimeMillis() - session.startedAt
        Log.d(TAG, "Stopping recording after ${durationMs / 1000}s")

        return try {
            recorder?.stop()
            releaseRecorder()
            state = RecordingState.IDLE
            activeSession = null
            RecordingResult.Stopped(session.file, durationMs)
        } catch (e: Exception) {
            // stop() can throw if called too quickly after start (< ~500ms of audio).
            // Release resources and return Stopped anyway — file may be incomplete.
            Log.w(TAG, "Recorder stop error (file may be incomplete): ${e.message}")
            releaseRecorder()
            state = RecordingState.IDLE
            activeSession = null
            RecordingResult.Stopped(session.file, durationMs)
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun buildRecorder(file: File): MediaRecorder {
        val rec: MediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        rec.setAudioSamplingRate(SAMPLE_RATE_HZ)
        rec.setAudioEncodingBitRate(BIT_RATE_BPS)
        rec.setOutputFile(file.absolutePath)
        return rec
    }

    private fun releaseRecorder() {
        try { recorder?.release() } catch (e: Exception) { Log.w(TAG, "Release error: ${e.message}") }
        recorder = null
    }
}
