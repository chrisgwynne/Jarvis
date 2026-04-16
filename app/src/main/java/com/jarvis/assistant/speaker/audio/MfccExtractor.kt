package com.jarvis.assistant.speaker.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * MfccExtractor — pure-Kotlin MFCC + delta + delta-delta speaker embedding.
 *
 * Input:  16 kHz, 16-bit signed mono PCM (ShortArray)
 * Output: FloatArray of length [EMBEDDING_DIM] (39) — the utterance-level mean
 *         of all frame-level features, L2-normalised for cosine similarity.
 *
 * Pipeline per utterance:
 *   1. Pre-emphasis filter (α = 0.97)
 *   2. 25 ms Hamming-windowed frames, 10 ms hop
 *   3. 512-point Cooley-Tukey FFT → one-sided power spectrum
 *   4. 40-filter Mel filterbank (80–7600 Hz)
 *   5. Log-energy per filter
 *   6. DCT-II → 13 cepstral coefficients
 *   7. Delta (Δ) and delta-delta (ΔΔ) — Δt = 2 frames
 *   8. Utterance mean across all frames → L2 normalised
 *
 * ACCURACY NOTE:
 *   Mean-MFCC embeddings work well for household speaker discrimination (2–5 people)
 *   but are not as accurate as neural speaker embeddings (d-vector, ECAPA-TDNN).
 *   Swap out [SpeakerEmbeddingEngine.extract] with a TFLite model for higher
 *   production accuracy — [EmbeddingCodec] and [SpeakerProfileStore] are model-agnostic.
 */
object MfccExtractor {

    const val SAMPLE_RATE   = 16_000
    const val EMBEDDING_DIM = 39   // 13 + 13 + 13

    private const val FRAME_SAMPLES  = 400    // 25 ms @ 16 kHz
    private const val HOP_SAMPLES    = 160    // 10 ms @ 16 kHz
    private const val FFT_SIZE       = 512    // must be power-of-2 ≥ FRAME_SAMPLES
    private const val NUM_MEL        = 40
    private const val NUM_CEPSTRAL   = 13
    private const val LOW_HZ         = 80.0
    private const val HIGH_HZ        = 7_600.0
    private const val PRE_EMPHASIS   = 0.97f
    private const val DELTA_WINDOW   = 2      // Δt for delta computation

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Extract a 39-dim speaker embedding from [pcm].
     * Returns null when audio is too short (< 0.5 s = 8 000 samples) or silent.
     * Minimum for reliable identification: ~1 s (16 000 samples).
     */
    fun extract(pcm: ShortArray): FloatArray? {
        if (pcm.size < SAMPLE_RATE / 2) return null
        if (isSilent(pcm))             return null

        val signal     = preEmphasis(pcm)
        val frames     = extractFrames(signal)
        if (frames.isEmpty()) return null

        val mfcc       = Array(frames.size) { mfccForFrame(frames[it]) }
        val delta      = computeDeltas(mfcc)
        val deltaDelta = computeDeltas(delta)

        return utteranceEmbedding(mfcc, delta, deltaDelta)
    }

    // ── Signal processing ─────────────────────────────────────────────────────

    private fun isSilent(pcm: ShortArray): Boolean {
        var energy = 0L
        for (s in pcm) energy += s.toLong() * s
        return energy / pcm.size < 100_000L   // ~316 RMS threshold
    }

    private fun preEmphasis(pcm: ShortArray): FloatArray {
        val out = FloatArray(pcm.size)
        out[0] = pcm[0].toFloat()
        for (i in 1 until pcm.size) out[i] = pcm[i] - PRE_EMPHASIS * pcm[i - 1]
        return out
    }

    private fun extractFrames(signal: FloatArray): List<FloatArray> {
        val frames = mutableListOf<FloatArray>()
        var start  = 0
        while (start + FRAME_SAMPLES <= signal.size) {
            val f = FloatArray(FRAME_SAMPLES)
            for (i in 0 until FRAME_SAMPLES) {
                val w = 0.54f - 0.46f * cos(2.0 * PI * i / (FRAME_SAMPLES - 1)).toFloat()
                f[i] = signal[start + i] * w
            }
            frames += f
            start  += HOP_SAMPLES
        }
        return frames
    }

    private fun mfccForFrame(frame: FloatArray): FloatArray {
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        for (i in frame.indices) re[i] = frame[i].toDouble()
        fft(re, im)

        val halfN = FFT_SIZE / 2 + 1
        val power = FloatArray(halfN) { k -> (re[k] * re[k] + im[k] * im[k]).toFloat() }

        val melBank = melFilterbank(power)
        val logMel  = FloatArray(NUM_MEL) { m -> ln(melBank[m].coerceAtLeast(1e-10f)) }
        return dct(logMel, NUM_CEPSTRAL)
    }

    // ── Cooley-Tukey radix-2 DIT FFT ─────────────────────────────────────────

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang); val wIm = kotlin.math.sin(ang)
            var i   = 0
            while (i < n) {
                var cRe = 1.0; var cIm = 0.0
                for (k in 0 until len / 2) {
                    val tRe = cRe * re[i+k+len/2] - cIm * im[i+k+len/2]
                    val tIm = cRe * im[i+k+len/2] + cIm * re[i+k+len/2]
                    re[i+k+len/2] = re[i+k] - tRe; im[i+k+len/2] = im[i+k] - tIm
                    re[i+k] += tRe;                  im[i+k] += tIm
                    val nextCRe = cRe * wRe - cIm * wIm
                    cIm = cRe * wIm + cIm * wRe; cRe = nextCRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    // ── Mel filterbank ────────────────────────────────────────────────────────

    private val melFilters: Array<FloatArray> by lazy { buildMelFilters() }

    private fun buildMelFilters(): Array<FloatArray> {
        val halfN   = FFT_SIZE / 2 + 1
        val lowMel  = hzToMel(LOW_HZ)
        val highMel = hzToMel(HIGH_HZ)
        val points  = FloatArray(NUM_MEL + 2) { i ->
            melToHz(lowMel + i * (highMel - lowMel) / (NUM_MEL + 1)).toFloat()
        }
        val binHz = FloatArray(halfN) { k -> k.toFloat() * SAMPLE_RATE / FFT_SIZE }

        return Array(NUM_MEL) { m ->
            val lo = points[m]; val center = points[m + 1]; val hi = points[m + 2]
            FloatArray(halfN) { k ->
                val f = binHz[k]
                when {
                    f < lo || f > hi -> 0f
                    f <= center      -> (f - lo) / (center - lo)
                    else             -> (hi - f) / (hi - center)
                }
            }
        }
    }

    private fun melFilterbank(power: FloatArray): FloatArray =
        FloatArray(NUM_MEL) { m -> var e = 0f; for (k in power.indices) e += power[k] * melFilters[m][k]; e }

    private fun hzToMel(hz: Double)  = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double)  = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    // ── DCT-II ────────────────────────────────────────────────────────────────

    private fun dct(x: FloatArray, numCoeff: Int): FloatArray {
        val N = x.size
        return FloatArray(numCoeff) { k ->
            var sum = 0.0
            for (n in 0 until N) sum += x[n] * cos(PI * k * (2 * n + 1) / (2 * N))
            sum.toFloat()
        }
    }

    // ── Delta (Δ) features ────────────────────────────────────────────────────

    private fun computeDeltas(matrix: Array<FloatArray>): Array<FloatArray> {
        val T = matrix.size
        val W = DELTA_WINDOW
        val denom = (1..W).sumOf { it * it } * 2
        return Array(T) { t ->
            FloatArray(matrix[0].size) { d ->
                var num = 0f
                for (dt in 1..W) {
                    val prev = matrix[(t - dt).coerceAtLeast(0)][d]
                    val next = matrix[(t + dt).coerceAtMost(T - 1)][d]
                    num += dt * (next - prev)
                }
                if (denom == 0) 0f else num / denom
            }
        }
    }

    // ── Utterance-level embedding ─────────────────────────────────────────────

    private fun utteranceEmbedding(
        base: Array<FloatArray>,
        delta: Array<FloatArray>,
        deltaDelta: Array<FloatArray>
    ): FloatArray {
        val T   = base.size
        val out = FloatArray(EMBEDDING_DIM)

        // Column-wise mean across all frames
        for (t in 0 until T) {
            for (i in 0 until NUM_CEPSTRAL) {
                out[i]                    += base[t][i]
                out[i + NUM_CEPSTRAL]     += delta[t][i]
                out[i + NUM_CEPSTRAL * 2] += deltaDelta[t][i]
            }
        }
        for (i in out.indices) out[i] /= T

        // L2 normalise so cosine similarity = dot product
        var norm = 0f
        for (v in out) norm += v * v
        norm = sqrt(norm)
        if (norm > 1e-8f) for (i in out.indices) out[i] /= norm

        return out
    }
}
