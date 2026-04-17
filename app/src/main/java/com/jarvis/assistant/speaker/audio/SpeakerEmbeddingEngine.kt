package com.jarvis.assistant.speaker.audio

import android.content.Context
import android.util.Log
import kotlin.math.sqrt

/**
 * SpeakerEmbeddingEngine — extracts embeddings from raw PCM and compares them.
 *
 * Prefers [NeuralEmbeddingEngine] (TFLite) when the model asset is present;
 * falls back to [MfccExtractor] (pure Kotlin, zero dependencies) otherwise.
 *
 * Call [init] once at runtime startup to load the neural model.  Thresholds
 * are automatically tightened when the neural engine is active.
 *
 * To integrate the neural model:
 *   1. Export an ECAPA-TDNN (or equivalent) speaker encoder to TFLite.
 *   2. Place the file at app/src/main/assets/speaker_encoder.tflite.
 *   3. Rebuild — [NeuralEmbeddingEngine.create] will find it automatically.
 *   4. Adjust [THRESHOLD_HIGH] / [THRESHOLD_LOW] if needed (defaults below
 *      are tuned for the ECAPA-TDNN 256-dim model).
 */
object SpeakerEmbeddingEngine {

    private const val TAG = "SpeakerEmbeddingEngine"

    private var neuralEngine: NeuralEmbeddingEngine? = null

    // ── Thresholds ────────────────────────────────────────────────────────────
    // Values are set in init() based on which engine loaded.

    /** Cosine similarity ≥ this → HIGH_CONFIDENCE_MATCH (silent trust). */
    var THRESHOLD_HIGH = 0.82f
        private set

    /** Cosine similarity ≥ this (and < HIGH) → LOW_CONFIDENCE — prompts "who's this?". */
    var THRESHOLD_LOW  = 0.70f
        private set

    /**
     * Minimum cosine similarity required for *silent* background enrollment.
     * Set above [THRESHOLD_HIGH] so that borderline matches are not added to
     * profiles — prevents gradual drift when a marginal false-positive slips
     * past the identification gate.
     */
    val SILENT_ENROLL_THRESHOLD get() = THRESHOLD_HIGH + 0.08f

    // ── Initialisation ────────────────────────────────────────────────────────

    /**
     * Attempt to load the TFLite neural speaker encoder.
     * Must be called once before any [extract] call (e.g. from JarvisRuntime.start).
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    fun init(context: Context) {
        if (neuralEngine != null) return
        val engine = NeuralEmbeddingEngine.create(context)
        if (engine != null) {
            neuralEngine   = engine
            THRESHOLD_HIGH = 0.90f
            THRESHOLD_LOW  = 0.75f
            Log.i(TAG, "Neural encoder loaded — HIGH=$THRESHOLD_HIGH LOW=$THRESHOLD_LOW")
        } else {
            Log.i(TAG, "MFCC fallback active — HIGH=$THRESHOLD_HIGH LOW=$THRESHOLD_LOW")
        }
    }

    // ── Embedding extraction ──────────────────────────────────────────────────

    /**
     * Extract a speaker embedding from raw 16 kHz 16-bit PCM.
     * Uses the neural engine when available, otherwise MFCC.
     * Returns null if the audio is too short or silent.
     */
    fun extract(pcm: ShortArray): FloatArray? =
        neuralEngine?.extract(pcm) ?: MfccExtractor.extract(pcm)

    // ── Similarity ────────────────────────────────────────────────────────────

    /**
     * Cosine similarity between two embedding vectors.
     * Both should be L2-normalised (produced by [extract]).
     * Returns 0 if either vector is zero or dimensions differ.
     */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) {
            Log.w(TAG, "Dimension mismatch: ${a.size} vs ${b.size} — skipping pair")
            return 0f
        }
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom < 1e-8f) 0f else (dot / denom).coerceIn(-1f, 1f)
    }

    // ── Identification ────────────────────────────────────────────────────────

    /**
     * Find the best-matching person for [probe] given a map of
     * personId → list of stored embeddings.
     *
     * Uses nearest-neighbour (max similarity over all stored embeddings for each
     * person).  Embeddings with a mismatched dimension are skipped silently —
     * this handles the transition period when old MFCC embeddings coexist with
     * new neural embeddings after a model upgrade; they simply score 0 and the
     * rolling 20-sample window will flush them over time.
     *
     * Returns null if [profiles] is empty or no embedding produces a score > 0.
     */
    fun bestMatch(
        probe   : FloatArray,
        profiles: Map<Long, List<FloatArray>>
    ): Pair<Long, Float>? {
        var bestId  = -1L
        var bestSim = 0f
        for ((personId, embeddings) in profiles) {
            for (stored in embeddings) {
                if (stored.size != probe.size) continue  // skip dimension mismatch (model transition)
                val sim = similarity(probe, stored)
                if (sim > bestSim) { bestSim = sim; bestId = personId }
            }
        }
        return if (bestId >= 0L) Pair(bestId, bestSim) else null
    }
}
