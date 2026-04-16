package com.jarvis.assistant.audio

import android.content.Context
import com.jarvis.assistant.audio.recording.AudioRecordingManager
import com.jarvis.assistant.audio.recording.RecordingResult
import com.jarvis.assistant.audio.recording.RecordingState
import com.jarvis.assistant.audio.recording.RecordingTranscriber
import com.jarvis.assistant.security.ActionPolicyGate
import com.jarvis.assistant.security.ActionType
import com.jarvis.assistant.security.PolicyResult
import com.jarvis.assistant.tools.device.AudioRecordingTool
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests for AudioRecordingTool.
 *
 * WHAT'S TESTED:
 *   - Policy gate registration (no hardware required)
 *   - Pattern matching for all four actions (no hardware required)
 *   - State-machine correctness using a mock AudioRecordingManager
 *
 * WHAT'S NOT TESTED HERE:
 *   - Actual MediaRecorder operation (hardware; covered by instrumented tests)
 *   - Whisper API call (network; covered by integration tests)
 */
class AudioRecordingToolTest {

    private val ctx: Context                     = mock()
    private val mockManager: AudioRecordingManager = mock()
    private val mockTranscriber: RecordingTranscriber = mock()

    private fun tool() = AudioRecordingTool(ctx, mockManager, mockTranscriber)

    // ── Policy gate ───────────────────────────────────────────────────────────

    @Test
    fun `audio_recording is approved by policy gate`() {
        assertTrue(ActionPolicyGate.isApproved("audio_recording"))
    }

    @Test
    fun `audio_recording maps to AUDIO_RECORDING action type`() {
        assertEquals(ActionType.AUDIO_RECORDING, ActionType.fromToolName("audio_recording"))
    }

    @Test
    fun `evaluate audio_recording returns ActionApproved`() {
        val result = ActionPolicyGate.evaluate("audio_recording", "start recording")
        assertTrue(result is PolicyResult.ActionApproved)
    }

    // ── Pattern matching ──────────────────────────────────────────────────────

    @Test
    fun `'start recording' matches start action`() {
        val input = tool().matches("start recording")
        assertNotNull(input)
        assertEquals("start", input!!.param("action"))
    }

    @Test
    fun `'begin recording' matches start action`() {
        val input = tool().matches("begin recording")
        assertNotNull(input)
        assertEquals("start", input!!.param("action"))
    }

    @Test
    fun `'record this' matches start action`() {
        val input = tool().matches("record this")
        assertNotNull(input)
        assertEquals("start", input!!.param("action"))
    }

    @Test
    fun `'stop recording' matches stop action`() {
        val input = tool().matches("stop recording")
        assertNotNull(input)
        assertEquals("stop", input!!.param("action"))
    }

    @Test
    fun `'end recording' matches stop action`() {
        val input = tool().matches("end recording")
        assertNotNull(input)
        assertEquals("stop", input!!.param("action"))
    }

    @Test
    fun `'transcribe the last recording' matches transcribe action`() {
        val input = tool().matches("transcribe the last recording")
        assertNotNull(input)
        assertEquals("transcribe", input!!.param("action"))
    }

    @Test
    fun `'what did i say' matches transcribe action`() {
        val input = tool().matches("what did i say")
        assertNotNull(input)
        assertEquals("transcribe", input!!.param("action"))
    }

    @Test
    fun `'summarize the last recording' matches summarize action`() {
        val input = tool().matches("summarize the last recording")
        assertNotNull(input)
        assertEquals("summarize", input!!.param("action"))
    }

    @Test
    fun `'what was the recording about' matches summarize action`() {
        val input = tool().matches("what was the recording about")
        assertNotNull(input)
        assertEquals("summarize", input!!.param("action"))
    }

    @Test
    fun `summarize is matched before transcribe for ambiguous phrases`() {
        // "summarize" should never be misclassified as "transcribe"
        val input = tool().matches("summarize the recording")
        assertEquals("summarize", input?.param("action"))
    }

    @Test
    fun `'call mum' does not match audio recording`() {
        assertNull(tool().matches("call mum"))
    }

    @Test
    fun `'play spotify' does not match audio recording`() {
        assertNull(tool().matches("play spotify"))
    }

    // ── State machine (via mock manager) ─────────────────────────────────────

    @Test
    fun `start when idle returns Success`() = runTest {
        whenever(mockManager.start()).thenReturn(RecordingResult.Started(mock()))
        whenever(mockManager.state).thenReturn(RecordingState.IDLE)

        val input = tool().matches("start recording")!!
        val result = tool().execute(input)

        assertTrue("Expected Success", result is com.jarvis.assistant.tools.framework.ToolResult.Success)
    }

    @Test
    fun `start when already recording returns Failure with message`() = runTest {
        whenever(mockManager.start()).thenReturn(RecordingResult.AlreadyRecording(mock()))
        whenever(mockManager.state).thenReturn(RecordingState.RECORDING)

        val input = tool().matches("start recording")!!
        val result = tool().execute(input)

        assertTrue("Expected Failure", result is com.jarvis.assistant.tools.framework.ToolResult.Failure)
        val failure = result as com.jarvis.assistant.tools.framework.ToolResult.Failure
        assertTrue("Message should mention 'Already'", failure.spokenFeedback.contains("Already", ignoreCase = true))
    }

    @Test
    fun `stop when not recording returns Failure with message`() = runTest {
        whenever(mockManager.stop()).thenReturn(RecordingResult.NotRecording)
        whenever(mockManager.state).thenReturn(RecordingState.IDLE)

        val input = tool().matches("stop recording")!!
        val result = tool().execute(input)

        assertTrue("Expected Failure", result is com.jarvis.assistant.tools.framework.ToolResult.Failure)
        val failure = result as com.jarvis.assistant.tools.framework.ToolResult.Failure
        assertTrue("Message should mention 'active'", failure.spokenFeedback.contains("active", ignoreCase = true))
    }

    @Test
    fun `stop when recording returns Success with duration`() = runTest {
        val dummyFile = java.io.File("/dev/null")
        whenever(mockManager.stop()).thenReturn(RecordingResult.Stopped(dummyFile, 12_000L))
        whenever(mockManager.state).thenReturn(RecordingState.RECORDING)

        val input = tool().matches("stop recording")!!
        val result = tool().execute(input)

        assertTrue("Expected Success", result is com.jarvis.assistant.tools.framework.ToolResult.Success)
        val success = result as com.jarvis.assistant.tools.framework.ToolResult.Success
        assertTrue("Should mention '12' seconds", success.spokenFeedback.contains("12"))
    }

    @Test
    fun `double start returns Failure not crash`() = runTest {
        whenever(mockManager.start()).thenReturn(RecordingResult.AlreadyRecording(mock()))

        val input = tool().matches("start recording")!!
        val result1 = tool().execute(input)
        val result2 = tool().execute(input)

        // Both calls must return a result — no crash, no state corruption
        assertNotNull(result1)
        assertNotNull(result2)
        assertTrue(result2 is com.jarvis.assistant.tools.framework.ToolResult.Failure)
    }
}
