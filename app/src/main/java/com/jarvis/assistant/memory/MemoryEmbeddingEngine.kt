package com.jarvis.assistant.memory

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Sentence embedding engine for semantic memory retrieval.
 *
 * Model: all-MiniLM-L6-v2-int8.tflite (place at app/src/main/assets/)
 * Input:  three int32 tensors [1×128] — input_ids, attention_mask, token_type_ids
 * Output: [1×384] float32 — L2-normalised sentence embedding
 *
 * Tokenisation uses a simplified hash-based approach (words → IDs mod vocab size).
 * Falls back to null across the board if the model asset is absent, allowing the
 * existing keyword+recency retrieval to continue working unchanged.
 */
class MemoryEmbeddingEngine private constructor(private val interpreter: Interpreter) {

    companion object {
        private const val TAG         = "MemoryEmbeddingEngine"
        private const val MODEL_ASSET = "all-MiniLM-L6-v2-int8.tflite"
        const  val EMBEDDING_DIM      = 384
        private const val MAX_SEQ_LEN = 128
        private const val VOCAB_SIZE  = 30_522  // standard BERT vocab size
        private const val CLS_TOKEN   = 101
        private const val SEP_TOKEN   = 102
        private const val PAD_TOKEN   = 0

        @Volatile private var instance: MemoryEmbeddingEngine? = null

        fun getInstance(context: Context): MemoryEmbeddingEngine? {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: try {
                    // Close the AssetFileDescriptor / FileInputStream / FileChannel
                    // deterministically after mmap() — they'd otherwise leak on
                    // every Jarvis cold-start.  The MappedByteBuffer keeps the
                    // underlying pages alive independently of the channel.
                    context.assets.openFd(MODEL_ASSET).use { fd ->
                        FileInputStream(fd.fileDescriptor).use { input ->
                            input.channel.use { channel ->
                                val buf = channel.map(
                                    FileChannel.MapMode.READ_ONLY,
                                    fd.startOffset,
                                    fd.declaredLength
                                )
                                MemoryEmbeddingEngine(Interpreter(buf)).also { instance = it }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Sentence embedding model not available — using keyword retrieval: ${e.message}")
                    null
                }
            }
        }

        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return 0f
            var dot = 0f; var na = 0f; var nb = 0f
            for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            val denom = sqrt(na) * sqrt(nb)
            return if (denom < 1e-8f) 0f else dot / denom
        }

        fun toByteArray(embedding: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(embedding.size * 4).order(ByteOrder.nativeOrder())
            embedding.forEach { buf.putFloat(it) }
            return buf.array()
        }

        fun fromByteArray(bytes: ByteArray): FloatArray {
            val buf   = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
            val count = bytes.size / 4
            return FloatArray(count) { buf.float }
        }
    }

    /**
     * Embed [text] into a [EMBEDDING_DIM]-dimensional L2-normalised vector.
     * Returns null if the interpreter fails.
     */
    fun embed(text: String): FloatArray? {
        val tokens = tokenize(text)

        val inputIds      = IntArray(MAX_SEQ_LEN) { PAD_TOKEN }
        val attentionMask = IntArray(MAX_SEQ_LEN) { 0 }
        val tokenTypeIds  = IntArray(MAX_SEQ_LEN) { 0 }

        inputIds[0]      = CLS_TOKEN
        attentionMask[0] = 1
        val used = minOf(tokens.size, MAX_SEQ_LEN - 2)
        for (i in 0 until used) {
            inputIds[i + 1]      = tokens[i]
            attentionMask[i + 1] = 1
        }
        inputIds[used + 1]      = SEP_TOKEN
        attentionMask[used + 1] = 1

        val idsBuffer  = intArrayToBuffer(inputIds)
        val maskBuffer = intArrayToBuffer(attentionMask)
        val typeBuffer = intArrayToBuffer(tokenTypeIds)
        val outputBuf  = ByteBuffer.allocateDirect(4 * EMBEDDING_DIM).order(ByteOrder.nativeOrder())

        return try {
            interpreter.runForMultipleInputsOutputs(
                arrayOf(idsBuffer, maskBuffer, typeBuffer),
                mapOf(0 to outputBuf)
            )
            outputBuf.rewind()
            val raw = FloatArray(EMBEDDING_DIM) { outputBuf.float }
            l2Normalize(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Embedding failed: ${e.message}")
            null
        }
    }

    private fun tokenize(text: String): IntArray {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { word -> (word.hashCode() and Int.MAX_VALUE) % VOCAB_SIZE + 1000 }
            .toIntArray()
    }

    private fun intArrayToBuffer(arr: IntArray): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder())
        arr.forEach { buf.putInt(it) }
        buf.rewind()
        return buf
    }

    private fun l2Normalize(v: FloatArray): FloatArray? {
        val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x })
        return if (norm < 1e-8f) null else FloatArray(v.size) { v[it] / norm }
    }

    fun close() = interpreter.close()
}
