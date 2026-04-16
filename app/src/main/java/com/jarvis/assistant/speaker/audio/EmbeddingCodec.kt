package com.jarvis.assistant.speaker.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Serialises FloatArray speaker embeddings to/from ByteArray for Room BLOB storage.
 * Uses little-endian IEEE 754 floats (4 bytes each).
 */
object EmbeddingCodec {

    fun encode(embedding: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(embedding.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        embedding.forEach { buf.putFloat(it) }
        return buf.array()
    }

    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size % Float.SIZE_BYTES == 0) {
            "Corrupt embedding blob: size ${bytes.size} is not a multiple of ${Float.SIZE_BYTES} bytes"
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / Float.SIZE_BYTES) { buf.getFloat() }
    }
}
