package com.jarvis.assistant.audio.wake

import kotlin.math.sqrt

/**
 * AmbientNoiseEstimator — rolling RMS estimator for the wake-word audio path.
 *
 * Pure, no Android dependencies, fully unit-testable.
 *
 * # Model
 *
 * Each call to [observe] feeds one PCM frame into:
 *   1. An RMS computation over the frame.
 *   2. An exponential moving average over recent RMS values.
 *
 * EMA was chosen over a fixed-window mean because:
 *   - O(1) memory per estimator regardless of window length.
 *   - No need to allocate / rotate a circular buffer on every frame.
 *   - Naturally weights recent activity higher (a 30-second-old jet engine
 *     should not still be pushing the threshold up).
 *
 * The estimator deliberately treats frames containing speech as part of the
 * ambient noise — they reflect what the recogniser has to push through.
 * Callers that want to exclude the user's own speech can call [pause]
 * around the active utterance.
 *
 * # Normalisation
 *
 * [noiseLevel] returns a 0..1 number computed as `currentRms / FULL_SCALE_RMS`
 * clamped to [0, 1].  At quiet room volume the value is typically < 0.05; a
 * loud TV pushes it past 0.2; speech directly at the mic ~0.4–0.7.
 */
class AmbientNoiseEstimator(
    /** EMA smoothing factor.  Default 0.05 → ~20-frame effective window. */
    private val alpha: Float = 0.05f,
    /** RMS value treated as 1.0 in [noiseLevel].  Default matches int16 PCM. */
    private val fullScaleRms: Float = 8000f
) {
    private var ema: Float = 0f
    @Volatile private var paused: Boolean = false

    /** Feed one frame of 16-bit signed PCM.  Non-blocking, O(N) over the frame. */
    fun observe(frame: ShortArray, length: Int = frame.size) {
        if (paused) return
        if (length <= 0) return
        val rms = computeRms(frame, length)
        ema = if (ema == 0f) rms else ema + alpha * (rms - ema)
    }

    /** Current normalised noise level, clamped to [0, 1]. */
    fun noiseLevel(): Float = (ema / fullScaleRms).coerceIn(0f, 1f)

    /** Raw RMS value (int16-scaled).  Useful for logs. */
    fun rawRms(): Float = ema

    /** Stop incorporating new frames — call around the user's spoken command. */
    fun pause() { paused = true }
    fun resume() { paused = false }

    /** Reset between sessions. */
    fun reset() { ema = 0f; paused = false }

    private fun computeRms(buf: ShortArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            val v = buf[i].toLong()
            sum += (v * v).toDouble()
        }
        return sqrt(sum / length).toFloat()
    }
}
