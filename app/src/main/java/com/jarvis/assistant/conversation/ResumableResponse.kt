package com.jarvis.assistant.conversation

/**
 * A response that was cut short by a user interruption and may be resumable.
 *
 * Populated by JarvisRuntime in streamAndSpeak() when handleBargeIn() fires.
 * Cleared as soon as the next turn is routed (either resumed or discarded).
 *
 * @property userTranscript       The user message that triggered this response.
 * @property spokenSoFar          What Jarvis managed to say before the interrupt.
 * @property pendingTail          Portion of the already-generated response that
 *                                 was NOT yet spoken (may be empty if the user
 *                                 barged in before the first full sentence).
 * @property topic                Short cue for log / UI (first line of response).
 * @property interruptedAt        System.currentTimeMillis() of the interrupt.
 * @property resumable            False if we know resuming wouldn't make sense
 *                                 (e.g. tool-bound responses, error messages).
 */
data class ResumableResponse(
    val userTranscript : String,
    val spokenSoFar    : String,
    val pendingTail    : String,
    val topic          : String,
    val interruptedAt  : Long = System.currentTimeMillis(),
    val resumable      : Boolean = true
) {
    /** True if this interrupt is older than [maxAgeMs] and should be forgotten. */
    fun isStale(maxAgeMs: Long = 30_000L): Boolean =
        System.currentTimeMillis() - interruptedAt > maxAgeMs
}
