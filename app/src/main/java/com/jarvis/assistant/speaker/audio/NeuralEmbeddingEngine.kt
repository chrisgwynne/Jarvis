package com.jarvis.assistant.speaker.audio

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Speaker embedding engine backed by a TFLite speaker encoder model.
 *
 * Expected model contract
 *   Input:  [1 × 16000] float32 — 1 second of 16 kHz mono PCM normalised to [-1, 1]
 *   Output: [1 × EMBEDDING_DIM] float32 — L2-normalised speaker embedding
 *
 * Place the TFLite model at:
 *   app/src/main/assets/speaker_encoder.tflite
 *
 * Recommended model: ECAPA-TDNN speaker verification exported to TFLite
 *   (256-dim output, ~4 MB, real-time on mid-range Android hardware).
 *   Thresholds in [SpeakerEmbeddingEngine] are automatically set to 0.90 / 0.75
 *   when this engine loads successfully.
 *
 * Falls back to null (caller uses [MfccExtractor]) if the asset is absent.
 */
class NeuralEmbeddingEngine private constructor(private val interpreter: Interpreter) {

    companion object {
        private const val TAG          = "NeuralEmbeddingEngine"
        private const val MODEL_ASSET  = "speaker_encoder.tflite"
        const  val EMBEDDING_DIM       = 256
        private const val SAMPLE_RATE  = 16_000
        private const val MIN_PCM_LEN  = SAMPLE_RATE / 2  // 0.5 s minimum

        fun create(context: Context): NeuralEmbeddingEngine? = try {
            val buffer = loadAsset(context, MODEL_ASSET)
            NeuralEmbeddingEngine(Interpreter(buffer))
        } catch (e: Exception) {
            Log.w(TAG, "Neural model not available — will use MFCC fallback: ${e.message}")
            null
        }

        private fun loadAsset(context: Context, name: String): MappedByteBuffer {
            val fd = context.assets.openFd(name)
            return FileInputStream(fd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
            )
        }
    }

    /**
     * Extract a [EMBEDDING_DIM]-dimensional L2-normalised speaker embedding.
     * Returns null if the audio is too short or silent.
     */
    fun extract(pcm: ShortArray): FloatArray? {
        if (pcm.size < MIN_PCM_LEN) return null

        val audioFloat = FloatArray(SAMPLE_RATE)
        val len = minOf(pcm.size, SAMPLE_RATE)
        for (i in 0 until len) audioFloat[i] = pcm[i] / 32768f

        val inputBuf = ByteBuffer.allocateDirect(4 * SAMPLE_RATE).apply {
            order(ByteOrder.nativeOrder())
            for (v in audioFloat) putFloat(v)
            rewind()
        }
        val outputBuf = ByteBuffer.allocateDirect(4 * EMBEDDING_DIM).apply {
            order(ByteOrder.nativeOrder())
        }

        interpreter.run(inputBuf, outputBuf)
        outputBuf.rewind()

        val embedding = FloatArray(EMBEDDING_DIM) { outputBuf.float }
        return l2Normalize(embedding)
    }

    private fun l2Normalize(v: FloatArray): FloatArray? {
        val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x })
        return if (norm < 1e-8f) null else FloatArray(v.size) { v[it] / norm }
    }

    fun close() = interpreter.close()
}
