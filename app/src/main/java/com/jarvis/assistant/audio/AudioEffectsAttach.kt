package com.jarvis.assistant.audio

import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * AudioEffectsAttach — best-effort attachment of software audio effects to
 * any [AudioRecord] session we open ourselves.
 *
 * Why this matters for STT accuracy:
 *   The Android [android.speech.SpeechRecognizer] uses its own internal mic
 *   path, so we can't decorate it directly.  But every other capture in this
 *   app (wake word, barge-in, speaker embedding) flows through [AudioRecord]
 *   and used to ignore the cheap-but-effective platform effects.  Enabling
 *   them costs almost nothing and noticeably reduces false wakes / false
 *   barge-ins from background noise.
 *
 * The returned [AudioEffect] handles must be released when the AudioRecord
 * is released — call [release] from the same teardown path.
 */
object AudioEffectsAttach {

    private const val TAG = "AudioEffectsAttach"

    /**
     * Attaches NoiseSuppressor + AcousticEchoCanceler + AutomaticGainControl
     * to [record]'s audio session, where each is reported available by the
     * platform.  Returns the list of created effects so the caller can
     * [release] them in lockstep with the AudioRecord.
     *
     * Safe to call on any API level; never throws.
     */
    fun attach(record: AudioRecord, tag: String = TAG): List<AudioEffect> {
        val sessionId = record.audioSessionId
        val effects = mutableListOf<AudioEffect>()

        if (NoiseSuppressor.isAvailable()) {
            try {
                NoiseSuppressor.create(sessionId)?.let {
                    it.enabled = true
                    effects.add(it)
                    Log.d(tag, "[AUDIO_FX] NoiseSuppressor attached (session=$sessionId)")
                }
            } catch (e: Throwable) {
                Log.w(tag, "[AUDIO_FX] NoiseSuppressor attach failed: ${e.message}")
            }
        } else {
            Log.d(tag, "[AUDIO_FX] NoiseSuppressor not available on this device")
        }

        if (AcousticEchoCanceler.isAvailable()) {
            try {
                AcousticEchoCanceler.create(sessionId)?.let {
                    it.enabled = true
                    effects.add(it)
                    Log.d(tag, "[AUDIO_FX] AcousticEchoCanceler attached (session=$sessionId)")
                }
            } catch (e: Throwable) {
                Log.w(tag, "[AUDIO_FX] AEC attach failed: ${e.message}")
            }
        } else {
            Log.d(tag, "[AUDIO_FX] AcousticEchoCanceler not available on this device")
        }

        if (AutomaticGainControl.isAvailable()) {
            try {
                AutomaticGainControl.create(sessionId)?.let {
                    it.enabled = true
                    effects.add(it)
                    Log.d(tag, "[AUDIO_FX] AutomaticGainControl attached (session=$sessionId)")
                }
            } catch (e: Throwable) {
                Log.w(tag, "[AUDIO_FX] AGC attach failed: ${e.message}")
            }
        } else {
            Log.d(tag, "[AUDIO_FX] AutomaticGainControl not available on this device")
        }

        return effects
    }

    /** Release every effect handle.  Never throws. */
    fun release(effects: List<AudioEffect>) {
        for (e in effects) {
            try { e.release() } catch (_: Throwable) {}
        }
    }
}
