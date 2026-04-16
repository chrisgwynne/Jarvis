package com.jarvis.assistant.speaker

import android.util.Log
import com.jarvis.assistant.speaker.audio.EmbeddingCodec
import com.jarvis.assistant.speaker.db.PersonRecord
import com.jarvis.assistant.speaker.db.PersonRecordDao
import com.jarvis.assistant.speaker.db.SpeakerEmbedding
import com.jarvis.assistant.speaker.db.SpeakerEmbeddingDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SpeakerProfileStore — repository for [PersonRecord]s and their voice embeddings.
 *
 * All methods are suspend and switch to [Dispatchers.IO] internally.
 */
class SpeakerProfileStore(
    private val personDao   : PersonRecordDao,
    private val embeddingDao: SpeakerEmbeddingDao
) {
    companion object {
        private const val TAG = "SpeakerProfileStore"
    }

    // ── Persons ───────────────────────────────────────────────────────────────

    suspend fun getAllPersons(): List<PersonRecord> =
        withContext(Dispatchers.IO) { personDao.getAll() }

    suspend fun getOwner(): PersonRecord? =
        withContext(Dispatchers.IO) { personDao.getOwner() }

    suspend fun getPersonById(id: Long): PersonRecord? =
        withContext(Dispatchers.IO) { personDao.getById(id) }

    /**
     * Create a new person with [displayName] and return their id.
     * Returns the existing id if a person with the same name already exists
     * (case-insensitive comparison).
     */
    suspend fun createPerson(displayName: String, isOwner: Boolean = false): Long =
        withContext(Dispatchers.IO) {
            val existing = personDao.getAll()
                .firstOrNull { it.displayName.equals(displayName, ignoreCase = true) }
            if (existing != null) {
                Log.d(TAG, "Person '${displayName}' already exists (id=${existing.id})")
                return@withContext existing.id
            }
            val id = personDao.insert(PersonRecord(displayName = displayName, isOwner = isOwner))
            Log.i(TAG, "Created person '$displayName' (id=$id, owner=$isOwner)")
            id
        }

    /** Delete a person and all their voice embeddings (cascaded by the FK). */
    suspend fun deletePerson(personId: Long) = withContext(Dispatchers.IO) {
        personDao.deleteById(personId)
        Log.i(TAG, "Deleted person id=$personId and all their embeddings")
    }

    suspend fun updateLastSeen(personId: Long) = withContext(Dispatchers.IO) {
        val p = personDao.getById(personId) ?: return@withContext
        personDao.update(p.copy(lastSeenAt = System.currentTimeMillis()))
    }

    // ── Voice embeddings ──────────────────────────────────────────────────────

    /**
     * Persist [embedding] for [personId], trim overflow rows, and refresh the
     * enrollment status on the [PersonRecord].
     */
    suspend fun addEmbedding(personId: Long, embedding: FloatArray) =
        withContext(Dispatchers.IO) {
            embeddingDao.insert(
                SpeakerEmbedding(
                    personId      = personId,
                    embeddingBlob = EmbeddingCodec.encode(embedding)
                )
            )
            embeddingDao.trimOldest(personId, SpeakerEmbedding.MAX_PER_PERSON)
            refreshEnrollmentStatus(personId)
        }

    /**
     * Load all stored embeddings, decoded to [FloatArray], keyed by person id.
     * Persons with no embeddings are present with an empty list.
     */
    suspend fun loadAllEmbeddings(): Map<Long, List<FloatArray>> =
        withContext(Dispatchers.IO) {
            personDao.getAll().associate { p ->
                p.id to embeddingDao.getAllForPerson(p.id).mapNotNull { row ->
                    try {
                        EmbeddingCodec.decode(row.embeddingBlob)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "Skipping corrupt embedding row ${row.id}: ${e.message}")
                        null
                    }
                }
            }
        }

    /**
     * True if at least one [PersonRecord] exists — i.e. the owner has completed
     * the name step of onboarding, even if no voice samples have been stored yet.
     * Use this to gate the first-run onboarding prompt.
     */
    suspend fun anyoneRegistered(): Boolean =
        withContext(Dispatchers.IO) { personDao.getAll().isNotEmpty() }

    /**
     * True if at least one person has one or more stored voice embeddings.
     * Use this to gate biometric speaker recognition / owner trust-mode bypass.
     */
    suspend fun anyoneEnrolled(): Boolean =
        withContext(Dispatchers.IO) {
            personDao.getAll().any { embeddingDao.countForPerson(it.id) > 0 }
        }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun refreshEnrollmentStatus(personId: Long) {
        val count  = embeddingDao.countForPerson(personId)
        val status = when {
            count >= 10 -> PersonRecord.EnrollmentStatus.ENROLLED
            count >= 3  -> PersonRecord.EnrollmentStatus.SUFFICIENT
            count >= 1  -> PersonRecord.EnrollmentStatus.TRAINING
            else        -> PersonRecord.EnrollmentStatus.NONE
        }
        val p = personDao.getById(personId) ?: return
        personDao.update(p.copy(
            enrolledUtteranceCount = count,
            enrollmentStatus       = status.name
        ))
        Log.d(TAG, "Enrollment status for '${p.displayName}': $count utterances → $status")
    }
}
