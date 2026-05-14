package com.jarvis.assistant.audio.stt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Smoke tests for [TranscriptCorrector.scoreToTier].  The thresholds are
 * a contract A4 depends on: HIGH ≥ 14, MEDIUM ≥ 6, else LOW.
 */
class TranscriptCorrectorScoreTierTest {

    @Test fun `score 14 is HIGH`() {
        assertEquals(TranscriptCorrector.ConfidenceTier.HIGH,
            TranscriptCorrector.scoreToTier(14))
    }

    @Test fun `score 22 (full local command) is HIGH`() {
        assertEquals(TranscriptCorrector.ConfidenceTier.HIGH,
            TranscriptCorrector.scoreToTier(22))
    }

    @Test fun `score 8 is MEDIUM`() {
        assertEquals(TranscriptCorrector.ConfidenceTier.MEDIUM,
            TranscriptCorrector.scoreToTier(8))
    }

    @Test fun `score 5 is LOW`() {
        assertEquals(TranscriptCorrector.ConfidenceTier.LOW,
            TranscriptCorrector.scoreToTier(5))
    }

    @Test fun `negative score is LOW`() {
        assertEquals(TranscriptCorrector.ConfidenceTier.LOW,
            TranscriptCorrector.scoreToTier(-5))
    }
}
