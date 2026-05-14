package com.jarvis.assistant.runtime.session

import com.jarvis.assistant.runtime.session.SessionContinuationPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionContinuationPolicyTest {

    /** Local alias — `V.X` reads cleanly inside the assertions below. */
    private object V {
        val CONTINUE_LISTENING   = Verdict.CONTINUE_LISTENING
        val STOP_LISTENING       = Verdict.STOP_LISTENING
        val STOP_TTS_ONLY        = Verdict.STOP_TTS_ONLY
        val ENTER_SILENT_MODE    = Verdict.ENTER_SILENT_MODE
    }

    // ── Core rule: local commands keep listening ───────────────────────────

    @Test
    fun `volume_control returns CONTINUE_LISTENING`() {
        assertEquals(V.CONTINUE_LISTENING,
            SessionContinuationPolicy.decide("volume_control", "turn the volume down"))
    }

    @Test
    fun `flashlight returns CONTINUE_LISTENING`() {
        assertEquals(V.CONTINUE_LISTENING,
            SessionContinuationPolicy.decide("flashlight", "turn on torch"))
    }

    @Test
    fun `media_control returns CONTINUE_LISTENING`() {
        assertEquals(V.CONTINUE_LISTENING,
            SessionContinuationPolicy.decide("media_control", "pause"))
    }

    @Test
    fun `time returns CONTINUE_LISTENING`() {
        assertEquals(V.CONTINUE_LISTENING,
            SessionContinuationPolicy.decide("time", "what time is it"))
    }

    @Test
    fun `smart_home returns CONTINUE_LISTENING`() {
        assertEquals(V.CONTINUE_LISTENING,
            SessionContinuationPolicy.decide("smart_home", "turn on kitchen light"))
    }

    // ── Session-ending tools ───────────────────────────────────────────────

    @Test
    fun `open_app returns STOP_LISTENING`() {
        assertEquals(V.STOP_LISTENING,
            SessionContinuationPolicy.decide("open_app", "open spotify"))
    }

    @Test
    fun `end_call returns STOP_LISTENING`() {
        assertEquals(V.STOP_LISTENING,
            SessionContinuationPolicy.decide("end_call", "hang up"))
    }

    @Test
    fun `camera_capture returns STOP_LISTENING`() {
        assertEquals(V.STOP_LISTENING,
            SessionContinuationPolicy.decide("camera_capture", "take a photo"))
    }

    @Test
    fun `music_search returns STOP_LISTENING`() {
        assertEquals(V.STOP_LISTENING,
            SessionContinuationPolicy.decide("music_search", "play hozier"))
    }

    // ── Explicit stop / silence phrases override the tool ──────────────────

    @Test
    fun `stop listening always stops`() {
        assertEquals(V.STOP_LISTENING,
            SessionContinuationPolicy.decide("volume_control", "stop listening"))
        assertEquals(V.STOP_LISTENING,
            SessionContinuationPolicy.decide(null, "stop listening"))
    }

    @Test
    fun `cancel always stops`() {
        assertEquals(V.STOP_LISTENING,
            SessionContinuationPolicy.decide("volume_control", "cancel"))
        assertEquals(V.STOP_LISTENING,
            SessionContinuationPolicy.decide(null, "never mind"))
    }

    @Test
    fun `be quiet enters silent mode`() {
        assertEquals(V.ENTER_SILENT_MODE,
            SessionContinuationPolicy.decide("volume_control", "be quiet"))
    }

    @Test
    fun `silence yourself enters silent mode`() {
        assertEquals(V.ENTER_SILENT_MODE,
            SessionContinuationPolicy.decide(null, "silence yourself"))
        assertEquals(V.ENTER_SILENT_MODE,
            SessionContinuationPolicy.decide(null, "shut up"))
        assertEquals(V.ENTER_SILENT_MODE,
            SessionContinuationPolicy.decide(null, "mute yourself"))
    }

    @Test
    fun `bare stop during TTS only stops TTS`() {
        assertEquals(V.STOP_TTS_ONLY,
            SessionContinuationPolicy.decide(null, "stop", ttsIsSpeaking = true))
    }

    @Test
    fun `bare stop without TTS keeps listening`() {
        assertEquals(V.CONTINUE_LISTENING,
            SessionContinuationPolicy.decide(null, "stop", ttsIsSpeaking = false))
    }

    // ── Predicate ──────────────────────────────────────────────────────────

    @Test
    fun `isExplicitStopOrSilence detects the canonical phrases`() {
        listOf("stop listening", "cancel", "never mind", "be quiet",
               "silence yourself", "shut up", "that's enough").forEach {
            assertTrue("'$it' should be explicit stop/silence",
                SessionContinuationPolicy.isExplicitStopOrSilence(it))
        }
        listOf("turn volume down", "what time is it", "open spotify").forEach {
            assertFalse("'$it' should NOT be explicit stop/silence",
                SessionContinuationPolicy.isExplicitStopOrSilence(it))
        }
    }
}
