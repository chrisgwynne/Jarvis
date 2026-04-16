package com.jarvis.assistant.speaker

import android.util.Log
import com.jarvis.assistant.speaker.audio.SpeakerEmbeddingEngine
import com.jarvis.assistant.speaker.db.PersonRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SpeakerRecognitionCoordinator — the single entry point for all speaker-identity
 * work in [JarvisRuntime].
 *
 * Responsibilities:
 *   1. Identify the speaker from raw PCM → [SpeakerIdentityResult]
 *   2. Parse a name from a free-text introduction reply ("I'm Chris")
 *   3. Create a [PersonRecord] + seed enrollment when a new person introduces themselves
 *   4. Enroll subsequent utterances to improve the voice profile over time
 *   5. Update last-seen timestamps for recognised speakers
 */
class SpeakerRecognitionCoordinator(
    private val store     : SpeakerProfileStore,
    private val enrollment: SpeakerEnrollmentManager
) {
    companion object {
        private const val TAG = "SpeakerCoordinator"

        /**
         * Patterns for extracting a name from an introduction reply.
         *
         * Accepts (case-insensitive):
         *   "I'm Chris"  |  "It's Cath"  |  "This is Dave"
         *   "My name is Sarah"  |  "My name's Sarah"  |  "The name's Bond"
         *   "Call me Sam"  |  "Just call me Sam"  |  bare single-word "Chris"
         */
        private val NAME_PATTERNS = listOf(
            Regex(
                """(?:i[' ]?m|it[' ]?s|this\s+is|my\s+name[' ]?s?(?:\s+is)?|the\s+name[' ]?s|(?:just\s+)?call\s+me)\s+([A-Za-z]+)""",
                RegexOption.IGNORE_CASE
            ),
            Regex("""^([A-Za-z]{2,24})$""")   // bare single word, 2–24 chars
        )
    }

    // ── Identification ────────────────────────────────────────────────────────

    /**
     * Identify the speaker from raw 16 kHz 16-bit PCM captured alongside the
     * SpeechRecognizer session.
     *
     * Returns [SpeakerIdentityResult.UNAVAILABLE] if:
     *  - no persons have any stored embeddings, or
     *  - the audio is too short/silent for embedding extraction.
     *
     * Runs on [Dispatchers.Default] (MFCC computation) + [Dispatchers.IO] (DB).
     */
    suspend fun identify(pcm: ShortArray): SpeakerIdentityResult =
        withContext(Dispatchers.Default) {
            val probe = SpeakerEmbeddingEngine.extract(pcm)
                ?: return@withContext SpeakerIdentityResult.UNAVAILABLE

            val profiles = withContext(Dispatchers.IO) { store.loadAllEmbeddings() }

            // Only persons with at least one embedding can be matched.
            val populated = profiles.filter { it.value.isNotEmpty() }
            if (populated.isEmpty()) return@withContext SpeakerIdentityResult.UNAVAILABLE

            val (personId, confidence) = SpeakerEmbeddingEngine.bestMatch(probe, populated)
                ?: return@withContext SpeakerIdentityResult.UNAVAILABLE

            val person = withContext(Dispatchers.IO) { store.getPersonById(personId) }
                ?: return@withContext SpeakerIdentityResult.UNAVAILABLE

            val band = when {
                confidence >= SpeakerEmbeddingEngine.THRESHOLD_HIGH ->
                    SpeakerIdentityResult.ConfidenceBand.HIGH_CONFIDENCE_MATCH
                confidence >= SpeakerEmbeddingEngine.THRESHOLD_LOW  ->
                    SpeakerIdentityResult.ConfidenceBand.LOW_CONFIDENCE_OR_AMBIGUOUS
                else ->
                    SpeakerIdentityResult.ConfidenceBand.UNKNOWN
            }

            Log.d(TAG, "Identified '${person.displayName}' " +
                       "confidence=${"%.2f".format(confidence)} band=$band")

            SpeakerIdentityResult(
                confidence  = confidence,
                // Do not expose the person's identity for UNKNOWN band —
                // the confidence was too low to be trustworthy.
                personId    = if (band == SpeakerIdentityResult.ConfidenceBand.UNKNOWN) null else personId,
                displayName = if (band == SpeakerIdentityResult.ConfidenceBand.UNKNOWN) null else person.displayName,
                band        = band
            )
        }

    // ── Introduction flow ─────────────────────────────────────────────────────

    /**
     * Extract a person's name from their introduction transcript.
     *
     * Returns null if no name could be parsed (caller should retry or skip).
     * The returned name has its first letter capitalised.
     */
    fun parseIntroductionName(transcript: String): String? {
        val trimmed = transcript.trim()
        for (pattern in NAME_PATTERNS) {
            val hit = pattern.find(trimmed) ?: continue
            val name = hit.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: continue
            return name.replaceFirstChar { it.uppercase() }
        }
        return null
    }

    /**
     * Create a [PersonRecord] for a newly introduced person and seed their voice
     * profile with the audio that triggered the introduction.
     *
     * [introductionPcm] is the PCM of the utterance that was identified as UNKNOWN —
     * enrolling it immediately gives the profile its first sample.
     *
     * @param isOwner  True when creating the device owner's record (first-run onboarding).
     *
     * Returns the freshly-created (or existing) [PersonRecord].
     */
    suspend fun createPersonFromIntroduction(
        displayName    : String,
        introductionPcm: ShortArray?,
        isOwner        : Boolean = false
    ): PersonRecord = withContext(Dispatchers.IO) {
        val personId = store.createPerson(displayName, isOwner = isOwner)
        val person   = store.getPersonById(personId)!!

        if (introductionPcm != null) {
            withContext(Dispatchers.Default) { enrollment.maybeEnroll(person, introductionPcm) }
        }
        person
    }

    /**
     * Add a confirmed utterance to an enrolled person's voice profile.
     * Called for every utterance from a HIGH or LOW confidence match to keep
     * the profile fresh.  Fire-and-forget — never blocks a pipeline response.
     */
    suspend fun enrollUtterance(personId: Long, pcm: ShortArray) {
        val person = withContext(Dispatchers.IO) { store.getPersonById(personId) }
            ?: return
        enrollment.maybeEnroll(person, pcm)
    }

    /** Update the last-seen timestamp for a recognised speaker. */
    suspend fun recordInteraction(personId: Long) = store.updateLastSeen(personId)

    /** Hint text about enrollment progress — safe to speak once after introduction. */
    fun enrollmentHint(status: PersonRecord.EnrollmentStatus): String =
        enrollment.progressHint(status)
}
