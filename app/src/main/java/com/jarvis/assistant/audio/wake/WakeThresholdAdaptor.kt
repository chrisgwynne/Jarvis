package com.jarvis.assistant.audio.wake

/**
 * WakeThresholdAdaptor — pure decision module that translates a normalised
 * ambient-noise level into a wake-word detection threshold.
 *
 * # Algorithm
 *
 *   adjusted = base + (max - base) * f(noise)
 *
 * where `f(noise)` is a piecewise-linear curve with hysteresis built in:
 *
 *   noise  ≤ QUIET_THRESHOLD       → adjusted = base       (no change)
 *   QUIET < noise < LOUD_THRESHOLD → linearly ramps to MAX
 *   noise  ≥ LOUD_THRESHOLD        → adjusted = MAX
 *
 * # Safety clamp
 *
 * The output is hard-capped at [MAX_THRESHOLD] (default 0.85).  Anything
 * higher would push the model output above its empirical p99 for true
 * positives — wake would never fire.  This is the "never make wake
 * impossible" invariant.
 *
 * # Hysteresis
 *
 * `currentThreshold` is mutated only when the proposed new value differs
 * from the existing one by more than [HYSTERESIS] (default 0.02), so a
 * jittery ambient signal doesn't make the threshold dance.
 *
 * Pure / no Android dependency / fully unit-testable.
 */
class WakeThresholdAdaptor(
    private val base:      Float = 0.50f,
    private val max:       Float = 0.85f,
    /** Below this noise level, the threshold stays at [base]. */
    private val quietThreshold: Float = 0.10f,
    /** At and above this noise level, the threshold is at [max]. */
    private val loudThreshold:  Float = 0.50f,
    /** Minimum change in proposed threshold before [adapt] updates state. */
    private val hysteresis: Float = 0.02f
) {
    init {
        require(base in 0f..1f)          { "base out of range: $base" }
        require(max  in 0f..1f && max >= base) { "max must be in [base..1]: $max" }
        require(quietThreshold in 0f..1f)
        require(loudThreshold  in 0f..1f && loudThreshold > quietThreshold)
        require(hysteresis in 0f..1f)
    }

    @Volatile private var current: Float = base

    /** Current threshold in [base..max]. */
    fun currentThreshold(): Float = current

    /**
     * Update [current] from the latest ambient noise level (0..1).
     * Returns the new threshold for convenience.
     */
    fun adapt(noiseLevel: Float): Float {
        val noise = noiseLevel.coerceIn(0f, 1f)
        val proposed = when {
            noise <= quietThreshold -> base
            noise >= loudThreshold  -> max
            else -> {
                val span     = loudThreshold - quietThreshold
                val fraction = (noise - quietThreshold) / span
                base + (max - base) * fraction
            }
        }
        if (kotlin.math.abs(proposed - current) >= hysteresis) {
            current = proposed.coerceAtMost(max)
        }
        return current
    }

    /** Reset to base — call when the wake session restarts. */
    fun reset() { current = base }
}
