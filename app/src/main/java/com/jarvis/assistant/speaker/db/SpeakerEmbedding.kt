package com.jarvis.assistant.speaker.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One utterance-level MFCC speaker embedding for a [PersonRecord].
 *
 * [embeddingBlob] is a serialised [FloatArray] of length [EMBEDDING_DIM] (39 values:
 * 13 MFCCs + 13 delta + 13 delta-delta), encoded little-endian by [EmbeddingCodec].
 *
 * During identification, ALL embeddings for each person are loaded and the
 * maximum cosine-similarity against the probe is taken.  This nearest-neighbour
 * approach beats a single centroid when a person's voice varies across recordings.
 *
 * Oldest rows are trimmed once a person accumulates more than [MAX_PER_PERSON]
 * samples — the profile keeps rolling so it self-updates over time.
 */
@Entity(
    tableName = "speaker_embeddings",
    foreignKeys = [ForeignKey(
        entity        = PersonRecord::class,
        parentColumns = ["id"],
        childColumns  = ["personId"],
        onDelete      = ForeignKey.CASCADE
    )],
    indices = [Index("personId"), Index("capturedAt")]
)
data class SpeakerEmbedding(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: Long,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val embeddingBlob: ByteArray,
    val capturedAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** Dimensions: 13 MFCC + 13 delta + 13 delta-delta. */
        const val EMBEDDING_DIM  = 39
        /** Rolling window: keep the 20 most-recent utterances per person. */
        const val MAX_PER_PERSON = 20
    }

    // ByteArray equality by content so data class comparisons work correctly.
    override fun equals(other: Any?) = other is SpeakerEmbedding && id == other.id
    override fun hashCode() = id.hashCode()
}
