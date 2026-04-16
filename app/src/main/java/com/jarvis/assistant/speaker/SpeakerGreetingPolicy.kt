package com.jarvis.assistant.speaker

/**
 * SpeakerGreetingPolicy — decides how Jarvis opens a new session.
 *
 * Rules (evaluated in order):
 *
 *   HIGH_CONFIDENCE_MATCH + greetByName=true  → "Hi [Name]"
 *   HIGH_CONFIDENCE_MATCH + greetByName=false → no greeting (caller chose silence)
 *   LOW_CONFIDENCE_OR_AMBIGUOUS               → neutral "Hi" — never wrong-name
 *   UNKNOWN + nobody enrolled yet             → neutral "Hi" (first-run, no profiles)
 *   UNKNOWN + someone is enrolled             → ask "Hi, who's this?"
 *
 * The greeting decision is made once per wake-word session.  Mid-session turns
 * do not re-greet.
 *
 * Actual TTS delivery is the caller's responsibility.
 */
object SpeakerGreetingPolicy {

    sealed class GreetingOutcome {
        /** Greet by the matched person's name. */
        data class NamedGreeting(val text: String) : GreetingOutcome()
        /** Say a generic "Hi" opener — identity uncertain or greeting by name disabled. */
        object NeutralGreeting : GreetingOutcome()
        /** Ask "Hi, who's this?" and await a name reply. */
        object AskForIntroduction : GreetingOutcome()
        /** No greeting spoken; activate silently. */
        object Silent : GreetingOutcome()
    }

    /**
     * @param result         Speaker identification result for this session.
     * @param anyoneEnrolled Whether at least one person has voice samples stored.
     *                       When false, asking "who's this?" is premature and confusing.
     * @param greetByName    Per-person override; set false to suppress name greetings.
     */
    fun computeGreeting(
        result         : SpeakerIdentityResult,
        anyoneEnrolled : Boolean,
        greetByName    : Boolean = true
    ): GreetingOutcome = when (result.band) {

        SpeakerIdentityResult.ConfidenceBand.HIGH_CONFIDENCE_MATCH ->
            if (greetByName && result.displayName != null)
                GreetingOutcome.NamedGreeting("Hi ${result.displayName}")
            else
                GreetingOutcome.NeutralGreeting

        SpeakerIdentityResult.ConfidenceBand.LOW_CONFIDENCE_OR_AMBIGUOUS ->
            GreetingOutcome.NeutralGreeting   // never use wrong name

        SpeakerIdentityResult.ConfidenceBand.UNKNOWN ->
            if (anyoneEnrolled) GreetingOutcome.AskForIntroduction
            else                GreetingOutcome.NeutralGreeting
    }
}
