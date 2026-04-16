package com.jarvis.assistant.speaker

import android.util.Log
import com.jarvis.assistant.speaker.audio.SpeakerEmbeddingEngine
import com.jarvis.assistant.speaker.db.PersonRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SpeakerEnrollmentManager — silently improves voice profiles over time.
 *
 * ENROLLMENT LIFECYCLE:
 *   1. Person created by the introduction flow (name but no voice samples).
 *   2. Each subsequent utterance is offered to [maybeEnroll].
 *   3. Enrollment continues until [PersonRecord.EnrollmentStatus.ENROLLED] (10+ samples).
 *   4. After full enrollment, new utterances still contribute up to the rolling
 *      window ([SpeakerEmbedding.MAX_PER_PERSON]) so the profile self-updates.
 *
 * Embedding extraction runs on [Dispatchers.Default].  Callers fire-and-forget
 * from within the existing pipeline — enrollment never blocks a user response.
 */
class SpeakerEnrollmentManager(private val store: SpeakerProfileStore) {

    companion object {
        private const val TAG = "SpeakerEnrollmentManager"
    }

    /**
     * Extract an embedding from [pcm] and add it to [person]'s profile.
     * Silent no-op if the audio is too short, silent, or extraction fails.
     */
    suspend fun maybeEnroll(person: PersonRecord, pcm: ShortArray) =
        withContext(Dispatchers.Default) {
            val embedding = SpeakerEmbeddingEngine.extract(pcm)
            if (embedding == null) {
                Log.d(TAG, "Skipping enrollment for '${person.displayName}': audio too short/silent")
                return@withContext
            }
            store.addEmbedding(person.id, embedding)
            Log.i(TAG, "Enrolled utterance for '${person.displayName}' " +
                       "(now ${person.enrolledUtteranceCount + 1} samples)")
        }

    /**
     * A brief spoken line about recognition progress, suitable for TTS.
     * Only surfaced to the user on their first introduction — not on every call.
     */
    fun progressHint(status: PersonRecord.EnrollmentStatus): String = when (status) {
        PersonRecord.EnrollmentStatus.NONE,
        PersonRecord.EnrollmentStatus.TRAINING   ->
            "I'll get better at recognising you over time."
        PersonRecord.EnrollmentStatus.SUFFICIENT ->
            "I'm starting to recognise your voice."
        PersonRecord.EnrollmentStatus.ENROLLED   ->
            "I should reliably recognise you now."
    }
}
