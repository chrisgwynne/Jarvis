package com.jarvis.assistant.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * BargeInDetector — monitors the microphone for voice activity while Jarvis is
 * speaking.  When sustained speech is detected, [onBargeIn] is fired on the
 * Main dispatcher so the caller can immediately cancel TTS and capture the
 * interruption utterance.
 *
 * ECHO CANCELLATION:
 *   Uses MediaRecorder.AudioSource.VOICE_RECOGNITION so Android treats this
 *   as an assistant microphone session rather than a call, which prevents
 *   media apps (Spotify, podcasts) from pausing when Jarvis starts listening.
 *   Software AEC/AGC/NS is applied via AudioEffectsAttach to suppress TTS
 *   playback bleed.  Hardware AEC (VOICE_COMMUNICATION source) is intentionally
 *   avoided because it signals a telephony-like session to the OS.
 *
 *   A mandatory [startDelayMs] cooldown (default 600 ms) further suppresses
 *   the initial TTS ramp-up before monitoring begins.
 *
 * DETECTION LOGIC:
 *   RMS energy of each audio frame is computed.  If RMS stays above
 *   [energyThreshold] for at least [holdMs] consecutive milliseconds,
 *   a barge-in is declared.  One detection per lifecycle (call start() again
 *   for the next TTS turn).
 *
 * HEADPHONE NOTES:
 *   Wired headsets: AEC + physical separation → very reliable.
 *   Bluetooth SCO:  AEC + hardware offload → reliable on most devices.
 *   Speaker:        AEC helps, but tune [energyThreshold] higher if false-triggers.
 *
 * THREADING:
 *   AudioRecord runs on Dispatchers.Default.
 *   [onBargeIn] is delivered on Dispatchers.Main.
 */
class BargeInDetector(
    private val onBargeIn: () -> Unit,
    private val energyThreshold: Double = 1800.0,  // RMS — high enough to ignore phone-speaker bleed
    // 300 ms sustained voice catches single-word barge-ins ("stop", "wait")
    // whose natural duration is 250–350 ms.  400 ms was too strict and caused
    // short commands to be missed.  Still long enough to reject door slams / taps.
    private val holdMs: Long = 300L,
    // 600 ms cooldown gives AEC time to adapt without gating out fast barge-ins
    // that happen at the very start of a long response.
    private val startDelayMs: Long = 600L,
    private val zcrMinHz: Double = 50.0,           // min zero-crossing rate for voiced speech
    // Raised from 300 Hz to 500 Hz so fricatives ("s" in "stop", "sh" in
    // "shush", the /dʒ/ onset of "Jarvis") don't push the frame out of the
    // voiced-speech window.
    private val zcrMaxHz: Double = 500.0
) {

    companion object {
        private const val TAG = "BargeInDetector"
        private const val SAMPLE_RATE = 16_000
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            com.jarvis.assistant.reporting.github.autoReporting("barge-in")
    )
    private var detectJob: Job? = null

    /**
     * Begin monitoring.
     * Safe to call multiple times — each call replaces the previous monitor.
     */
    fun start() {
        stop()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR_BAD_VALUE || minBuf == AudioRecord.ERROR) {
            Log.w(TAG, "AudioRecord not supported on this device")
            return
        }

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 4    // larger buffer reduces chance of overruns
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord init failed (state=${record.state})")
            record.release()
            return
        }

        // Software AEC / AGC / NS on top of VOICE_RECOGNITION.
        // AEC in particular suppresses TTS playback bleed that could otherwise
        // trigger false barge-ins when the speaker is in use.
        val effects = AudioEffectsAttach.attach(record, TAG)

        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            // Device denied the record start (mic held by another app, focus lost
            // mid-init, etc.).  Release the claim so we don't pin the hardware.
            Log.w(TAG, "AudioRecord.startRecording() threw — releasing", e)
            AudioEffectsAttach.release(effects)
            record.release()
            return
        }
        Log.d(TAG, "[BARGE_IN_ARMED] threshold=$energyThreshold holdMs=$holdMs")
        Log.d(TAG, "Monitoring started (threshold=$energyThreshold, holdMs=$holdMs)")

        detectJob = scope.launch {
            try {
                delay(startDelayMs)   // wait for TTS to ramp up before monitoring

                val buf = ShortArray(minBuf)
                var sustainStart = 0L
                var lastVoiceTime = 0L
                // Allow up to 80 ms of silence mid-word (unvoiced stop consonants, breath)
                // before resetting the sustain clock.  Without this, "stop" → [s][t][op]
                // can have a brief dip on the plosive that zeros sustainStart prematurely.
                val gapToleranceMs = 80L

                while (isActive) {
                    val read = record.read(buf, 0, buf.size)
                    if (read <= 0) { delay(10); continue }

                    val rms    = rms(buf, read)
                    val zcr    = zcr(buf, read)
                    val isVoice = rms >= energyThreshold &&
                                  zcr >= zcrMinHz &&
                                  zcr <= zcrMaxHz

                    val now = System.currentTimeMillis()
                    if (isVoice) {
                        if (sustainStart == 0L) sustainStart = now
                        lastVoiceTime = now
                        val held = now - sustainStart
                        if (held >= holdMs) {
                            Log.d(TAG, "Barge-in! RMS=%.0f ZCR=%.0f sustained=${held}ms".format(rms, zcr))
                            withContext(Dispatchers.Main) { onBargeIn() }
                            break
                        }
                    } else if (now - lastVoiceTime > gapToleranceMs) {
                        // Gap exceeds tolerance — genuine silence, reset sustain clock
                        sustainStart = 0L
                    }
                }
            } finally {
                AudioEffectsAttach.release(effects)
                record.stop()
                record.release()
                Log.d(TAG, "Monitoring stopped")
            }
        }
    }

    /** Stop monitoring and release resources. */
    fun stop() {
        detectJob?.cancel()
        detectJob = null
    }

    fun release() {
        stop()
        scope.cancel()
    }

    // ── Signal processing ─────────────────────────────────────────────────────

    private fun rms(buf: ShortArray, len: Int): Double {
        if (len == 0) return 0.0
        var sum = 0.0
        for (i in 0 until len) sum += buf[i].toLong() * buf[i].toLong()
        return sqrt(sum / len)
    }

    /**
     * Zero-crossing rate in Hz (crossings per second).
     * Voiced speech: ~50–300 Hz.
     * Broadband noise / hiss: >>300 Hz.
     * Low-frequency rumble / thumps: <50 Hz.
     */
    private fun zcr(buf: ShortArray, len: Int): Double {
        if (len <= 1) return 0.0
        var crossings = 0
        for (i in 1 until len) {
            if ((buf[i - 1] >= 0) != (buf[i] >= 0)) crossings++
        }
        return crossings.toDouble() / len * SAMPLE_RATE
    }
}
