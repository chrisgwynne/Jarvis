package com.jarvis.assistant.speaker.audio

import android.util.Log
import kotlin.math.sqrt

/**
 * SpeakerEmbeddingEngine — extracts embeddings from raw PCM and compares them.
 *
 * Currently backed by [MfccExtractor] (pure Kotlin, zero extra dependencies).
 * To upgrade to a neural speaker model (ECAPA-TDNN, d-vector, etc.):
 *   1. Replace [extract] with TFLite inference.
 *   2. Adjust [THRESHOLD_HIGH] and [THRESHOLD_LOW] — neural models give tighter
 *      cosine distributions and support higher thresholds (e.g. 0.90 / 0.75).
 *   3. Keep [similarity] and [bestMatch] unchanged — they are model-agnostic.
 */
object SpeakerEmbeddingEngine {

    private const val TAG = "SpeakerEmbeddingEngine"

    /**
     * Cosine similarity ≥ this → HIGH_CONFIDENCE_MATCH.
     * Tuned conservatively for MFCC embeddings; tighten for neural models.
     */
    const val THRESHOLD_HIGH = 0.82f

    /**
     * Cosine similarity ≥ this (and < THRESHOLD_HIGH) → LOW_CONFIDENCE_OR_AMBIGUOUS.
     * Below this → UNKNOWN.
     */
    const val THRESHOLD_LOW  = 0.60f

    // ── Embedding extraction ──────────────────────────────────────────────────

    /**
     * Extract a 39-dim speaker embedding from raw 16 kHz 16-bit PCM.
     * Returns null if the audio is too short or silent to be useful.
     */
    fun extract(pcm: ShortArray): FloatArray? = MfccExtractor.extract(pcm)

    // ── Similarity ────────────────────────────────────────────────────────────

    /**
     * Cosine similarity between two embedding vectors.
     * Both should be L2-normalised (produced by [extract]).
     * Returns 0 if either vector is zero or dimensions differ.
     */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) {
            Log.w(TAG, "Dimension mismatch: ${a.size} vs ${b.size}")
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
     * person) rather than centroid comparison — better handles intra-speaker
     * variation across recording conditions.
     *
     * Returns null if [profiles] is empty.
     * Returns Pair(personId, similarity) for the best match.
     */
    fun bestMatch(
        probe: FloatArray,
        profiles: Map<Long, List<FloatArray>>
    ): Pair<Long, Float>? {
        var bestId  = -1L
        var bestSim = 0f
        for ((personId, embeddings) in profiles) {
            for (stored in embeddings) {
                val sim = similarity(probe, stored)
                if (sim > bestSim) { bestSim = sim; bestId = personId }
            }
        }
        return if (bestId >= 0L) Pair(bestId, bestSim) else null
    }
}
