package com.jarvis.assistant.speaker.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * SpeakerAudioCapture — captures raw 16 kHz 16-bit mono PCM in a ring buffer,
 * running concurrently alongside Android's SpeechRecognizer (API 29+).
 *
 * WHY REQUIRES API 29?
 *   Android 10 introduced concurrent microphone access: multiple apps/processes
 *   can read the mic simultaneously.  As the active foreground service, Jarvis
 *   has priority access.  On API < 29 the two sessions would conflict, so we
 *   skip speaker recognition gracefully on older devices.
 *
 * USAGE:
 *   val cap = SpeakerAudioCapture()
 *   cap.start()                        // begin buffering — non-blocking
 *   speechCapture.listen()             // concurrent with SpeechRecognizer
 *   val pcm: ShortArray? = cap.stop()  // flush ring buffer; null if unsupported
 *
 * [stop] returns everything captured since [start], up to [maxSeconds] of audio.
 * All errors are caught and logged — never throws.
 */
class SpeakerAudioCapture(private val maxSeconds: Int = 10) {

    companion object {
        private const val TAG         = "SpeakerAudioCapture"
        private const val SAMPLE_RATE = 16_000

        /** False on API < 29 — concurrent capture is not available. */
        val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    private val maxSamples  = SAMPLE_RATE * maxSeconds
    private val ringBuffer  = ShortArray(maxSamples)
    @Volatile private var writePos    = 0
    @Volatile private var sampleCount = 0

    private var audioRecord : AudioRecord? = null
    private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureJob  : Job? = null

    /**
     * Begin capturing.  No-op if the device doesn't support concurrent capture
     * (API < 29), if the mic permission is missing, or if already running.
     */
    fun start() {
        if (!isSupported) return
        if (captureJob?.isActive == true) return

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) { Log.w(TAG, "AudioRecord not available (minBuf=$minBuf)"); return }

        try {
            // VOICE_RECOGNITION is the same hardware source that SpeechRecognizer
            // uses internally.  Android 10+ explicitly supports multiple concurrent
            // VOICE_RECOGNITION sessions, so this does not preempt the recogniser.
            // Using MIC here would risk grabbing the hardware before the recogniser
            // has a chance to start, causing it to capture silence.
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4   // generous buffer to avoid overruns
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord failed to initialise (state=${record.state}) — mic likely busy")
                record.release(); return
            }
            audioRecord = record
            writePos    = 0
            sampleCount = 0
            record.startRecording()
            Log.d(TAG, "SpeakerAudioCapture started")

            captureJob = captureScope.launch {
                val buf = ShortArray(minBuf)
                while (isActive) {
                    val read = record.read(buf, 0, buf.size)
                    if (read > 0) appendSamples(buf, read)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Mic permission denied: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "SpeakerAudioCapture.start error: ${e.message}")
        }
    }

    /**
     * Stop capture and return the collected PCM.
     * Returns null if capture was not started, not supported, or nothing was recorded.
     */
    fun stop(): ShortArray? {
        captureJob?.cancel()
        captureJob = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        val count = sampleCount.coerceAtMost(maxSamples)
        Log.d(TAG, "SpeakerAudioCapture stopped ($count samples)")
        return if (count == 0) null else ringBuffer.copyOf(count)
    }

    fun release() {
        stop()
        captureScope.cancel()
    }

    @Synchronized
    private fun appendSamples(buf: ShortArray, len: Int) {
        for (i in 0 until len) {
            ringBuffer[writePos % maxSamples] = buf[i]
            writePos++
            if (sampleCount < maxSamples) sampleCount++
        }
    }
}
