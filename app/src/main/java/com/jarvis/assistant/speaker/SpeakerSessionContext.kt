package com.jarvis.assistant.speaker

/**
 * Per-session speaker state.  Created fresh at each wake-word activation and
 * discarded when the session ends.
 *
 * Lives in [JarvisRuntime] and is threaded through to [PromptAssembler] and
 * the tool-permission gate on every turn.
 *
 * @param result                    The identification result for this session's speaker.
 * @param askedForIntroduction      True if we have already said "Hi, who's this?" this session.
 * @param awaitingIntroductionReply True if the user has not yet replied with their name.
 * @param pendingPcm                Raw PCM of the utterance that triggered an UNKNOWN result —
 *                                  used to enroll that audio once the name is known.
 * @param awaitingOwnerName            True during first-run onboarding: Jarvis asked for the
 *                                     owner's name and is waiting for the reply.
 * @param awaitingOwnerVoiceSample     True during first-run onboarding step 2: owner name stored,
 *                                     Jarvis asked for a sample sentence to seed voice recognition.
 * @param pendingOwnerName             Owner's name captured in step 1, used in step 2 response.
 * @param awaitingGuestEnrollmentSample True when a guest said "remember me" — Jarvis asked for
 *                                     a voice sample and is waiting for the utterance to enroll.
 * @param awaitingVoiceEnrollmentSample True when the user triggered a manual "train my voice"
 *                                     enrollment session — Jarvis is collecting sample utterances.
 * @param voiceEnrollmentSamplesRemaining How many more sample utterances still need to be
 *                                     collected in the current manual enrollment session.
 */
data class SpeakerSessionContext(
    val result: SpeakerIdentityResult = SpeakerIdentityResult.UNAVAILABLE,
    val askedForIntroduction: Boolean = false,
    val awaitingIntroductionReply: Boolean = false,
    val pendingPcm: ShortArray? = null,
    val awaitingOwnerName: Boolean = false,
    val awaitingOwnerVoiceSample: Boolean = false,
    val pendingOwnerName: String? = null,
    val awaitingGuestEnrollmentSample: Boolean = false,
    val awaitingVoiceEnrollmentSample: Boolean = false,
    val voiceEnrollmentSamplesRemaining: Int = 0
) {
    val isHighConfidence: Boolean
        get() = result.band == SpeakerIdentityResult.ConfidenceBand.HIGH_CONFIDENCE_MATCH

    val isKnown: Boolean get() = result.isKnown
}
