package com.jarvis.assistant.personality

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SeriousModeDetectorTest — pin the keyword + context hint classifier.
 * False positives are far less harmful than false negatives, so the
 * test set is biased toward "must definitely trigger" cases.
 */
class SeriousModeDetectorTest {

    @Test fun `emergency phrases trigger`() {
        listOf(
            "call 999",
            "call the police",
            "I'm having a panic attack",
            "my son is missing",
            "someone has died",
            "I'm having chest pain",
            "there's an intruder downstairs",
            "I want to die",
        ).forEach {
            assertTrue("'$it' must trigger serious mode",
                SeriousModeDetector.isSerious(it))
        }
    }

    @Test fun `casual utterances do not trigger`() {
        listOf(
            "set a timer for 5 minutes",
            "what time is it",
            "remind me to put bins out",
            "turn the lights off",
            "send Mike a whatsapp",
            "play some music",
        ).forEach {
            assertFalse("'$it' must NOT trigger serious mode",
                SeriousModeDetector.isSerious(it))
        }
    }

    @Test fun `explicit user request triggers`() {
        listOf("serious mode on", "no jokes please", "stop joking",
               "be serious").forEach {
            assertTrue("'$it' should trigger", SeriousModeDetector.isSerious(it))
        }
    }

    @Test fun `context hint alone triggers regardless of text`() {
        assertTrue(SeriousModeDetector.isSerious(
            transcript  = "set a timer",
            contextHint = "emergency",
        ))
        assertTrue(SeriousModeDetector.isSerious(
            transcript  = null,
            contextHint = "intruder_detected",
        ))
    }

    @Test fun `null and blank transcripts are not serious by default`() {
        assertFalse(SeriousModeDetector.isSerious(null))
        assertFalse(SeriousModeDetector.isSerious(""))
        assertFalse(SeriousModeDetector.isSerious("   "))
    }
}
