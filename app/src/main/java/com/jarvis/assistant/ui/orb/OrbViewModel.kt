package com.jarvis.assistant.ui.orb

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.core.store.DeviceStateStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlin.math.PI
import kotlin.math.sin

/**
 * Bridges the runtime layer ([DeviceStateStore]) to the UI orb layer.
 *
 * Exposes two streams:
 *  - [visualState] — the current [OrbVisualState] derived from [JarvisState]
 *  - [amplitude]   — synthetic 0..1 amplitude at ~30 fps, driven by layered
 *                    harmonics for Listening and Speaking states
 *
 * The amplitude computation does NOT open AudioRecord; it uses pure math so
 * that no additional mic resource conflicts arise in the UI process.
 */
class OrbViewModel : ViewModel() {

    val visualState: StateFlow<OrbVisualState> = DeviceStateStore.state
        .map { OrbStateMapper.map(it.runtimeState) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, OrbVisualState.Dormant)

    /**
     * Synthetic amplitude emitted at ~30 fps.
     *
     * Listening  — gentle layered harmonics at 1.2 / 2.4 / 3.6 Hz → 0.15..0.45
     * Speaking   — energetic harmonics at 2.5 / 5.0 / 7.5 / 10 Hz → 0.30..1.00
     * Everything else → 0f (waveform not drawn anyway)
     */
    val amplitude: StateFlow<Float> = flow {
        var tMs = 0L
        while (true) {
            emit(computeAmplitude(visualState.value, tMs))
            delay(33L)
            tMs += 33L
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun computeAmplitude(state: OrbVisualState, tMs: Long): Float = when (state) {

        is OrbVisualState.Listening -> {
            // Soft ripple — "ready and waiting" feel
            val h1  = sin(2.0 * PI * 1.2 * tMs / 1000.0)
            val h2  = sin(2.0 * PI * 2.4 * tMs / 1000.0) * 0.50
            val h3  = sin(2.0 * PI * 3.6 * tMs / 1000.0) * 0.33
            val raw = ((h1 + h2 + h3) / 1.83).toFloat()   // -1f..1f
            0.15f + (raw + 1f) * 0.15f                     //  0.15..0.45
        }

        is OrbVisualState.Speaking -> {
            // Energetic harmonics — simulates voice output
            val h1  = sin(2.0 * PI * 2.5  * tMs / 1000.0)
            val h2  = sin(2.0 * PI * 5.0  * tMs / 1000.0) * 0.50
            val h3  = sin(2.0 * PI * 7.5  * tMs / 1000.0) * 0.33
            val h4  = sin(2.0 * PI * 10.0 * tMs / 1000.0) * 0.20
            val raw = ((h1 + h2 + h3 + h4) / 2.03).toFloat()
            0.30f + (raw + 1f) * 0.35f                     //  0.30..1.00
        }

        else -> 0f
    }
}
