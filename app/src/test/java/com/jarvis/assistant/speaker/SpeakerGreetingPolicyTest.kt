package com.jarvis.assistant.speaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerGreetingPolicyTest {

    private fun highResult(name: String) = SpeakerIdentityResult(
        confidence  = 0.95f,
        personId    = 1L,
        displayName = name,
        band        = SpeakerIdentityResult.ConfidenceBand.HIGH_CONFIDENCE_MATCH
    )

    private val lowResult = SpeakerIdentityResult(
        confidence  = 0.70f,
        personId    = 2L,
        displayName = "Bob",
        band        = SpeakerIdentityResult.ConfidenceBand.LOW_CONFIDENCE_OR_AMBIGUOUS
    )

    private val unknownResult = SpeakerIdentityResult.UNAVAILABLE

    @Test fun `high confidence with name and greetByName true gives named greeting`() {
        val outcome = SpeakerGreetingPolicy.computeGreeting(highResult("Chris"), anyoneEnrolled = true)
        assertTrue(outcome is SpeakerGreetingPolicy.GreetingOutcome.NamedGreeting)
        assertEquals("Hi Chris", (outcome as SpeakerGreetingPolicy.GreetingOutcome.NamedGreeting).text)
    }

    @Test fun `high confidence with greetByName false gives neutral greeting`() {
        val outcome = SpeakerGreetingPolicy.computeGreeting(highResult("Chris"), anyoneEnrolled = true, greetByName = false)
        assertEquals(SpeakerGreetingPolicy.GreetingOutcome.NeutralGreeting, outcome)
    }

    @Test fun `high confidence with null display name gives neutral greeting`() {
        val result = SpeakerIdentityResult(0.95f, 1L, null, SpeakerIdentityResult.ConfidenceBand.HIGH_CONFIDENCE_MATCH)
        val outcome = SpeakerGreetingPolicy.computeGreeting(result, anyoneEnrolled = true)
        assertEquals(SpeakerGreetingPolicy.GreetingOutcome.NeutralGreeting, outcome)
    }

    @Test fun `low confidence always gives neutral greeting`() {
        val outcome = SpeakerGreetingPolicy.computeGreeting(lowResult, anyoneEnrolled = true)
        assertEquals(SpeakerGreetingPolicy.GreetingOutcome.NeutralGreeting, outcome)
    }

    @Test fun `unknown speaker with someone enrolled asks for introduction`() {
        val outcome = SpeakerGreetingPolicy.computeGreeting(unknownResult, anyoneEnrolled = true)
        assertEquals(SpeakerGreetingPolicy.GreetingOutcome.AskForIntroduction, outcome)
    }

    @Test fun `unknown speaker with nobody enrolled gives neutral greeting`() {
        val outcome = SpeakerGreetingPolicy.computeGreeting(unknownResult, anyoneEnrolled = false)
        assertEquals(SpeakerGreetingPolicy.GreetingOutcome.NeutralGreeting, outcome)
    }
}
