package com.jarvis.assistant.speaker.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SpeakerEmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(embedding: SpeakerEmbedding): Long

    @Query("SELECT * FROM speaker_embeddings WHERE personId = :personId ORDER BY capturedAt DESC")
    suspend fun getAllForPerson(personId: Long): List<SpeakerEmbedding>

    @Query("SELECT COUNT(*) FROM speaker_embeddings WHERE personId = :personId")
    suspend fun countForPerson(personId: Long): Int

    /**
     * Trim oldest rows so [personId] never exceeds [maxCount] embeddings.
     * Keeps the most-recent [maxCount] by capturedAt.
     */
    @Query(
        "DELETE FROM speaker_embeddings WHERE personId = :personId AND id NOT IN " +
        "(SELECT id FROM speaker_embeddings WHERE personId = :personId " +
        "ORDER BY capturedAt DESC LIMIT :maxCount)"
    )
    suspend fun trimOldest(personId: Long, maxCount: Int)

    @Query("DELETE FROM speaker_embeddings WHERE personId = :personId")
    suspend fun deleteAllForPerson(personId: Long)
}
