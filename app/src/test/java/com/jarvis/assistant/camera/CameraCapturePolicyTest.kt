package com.jarvis.assistant.camera

import android.content.Context
import com.jarvis.assistant.security.ActionPolicyGate
import com.jarvis.assistant.security.ActionType
import com.jarvis.assistant.security.PolicyResult
import com.jarvis.assistant.tools.device.AnalyzeCameraViewTool
import com.jarvis.assistant.tools.device.CameraCaptureTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Verifies:
 *   1. Camera tools appear in the policy allowlist.
 *   2. CameraCaptureTool.matches() routes rear/front correctly (no hardware needed).
 *   3. AnalyzeCameraViewTool.matches() triggers on the right phrases.
 *
 * No Android hardware is exercised — execute() is never called here.
 */
class CameraCapturePolicyTest {

    // Mockito mocks for constructor params that matches() never touches
    private val ctx: Context              = mock()
    private val capture: CameraCaptureManager = mock()
    private val vision: VisionClient      = mock()

    // ── Policy gate ───────────────────────────────────────────────────────────

    @Test
    fun `camera_capture is approved by policy gate`() {
        assertTrue(ActionPolicyGate.isApproved("camera_capture"))
    }

    @Test
    fun `analyze_camera_view is approved by policy gate`() {
        assertTrue(ActionPolicyGate.isApproved("analyze_camera_view"))
    }

    @Test
    fun `camera_capture maps to CAPTURE_PHOTO action type`() {
        assertEquals(ActionType.CAPTURE_PHOTO, ActionType.fromToolName("camera_capture"))
    }

    @Test
    fun `analyze_camera_view maps to ANALYZE_CAMERA_VIEW action type`() {
        assertEquals(ActionType.ANALYZE_CAMERA_VIEW, ActionType.fromToolName("analyze_camera_view"))
    }

    @Test
    fun `evaluate camera_capture returns ActionApproved`() {
        val result = ActionPolicyGate.evaluate("camera_capture", "take a photo")
        assertTrue("Expected ActionApproved but got $result", result is PolicyResult.ActionApproved)
    }

    @Test
    fun `unknown camera tool name returns ActionUnsupported not silent drop`() {
        val result = ActionPolicyGate.evaluate("hidden_camera_loop", "capture background")
        assertTrue("Expected ActionUnsupported", result is PolicyResult.ActionUnsupported)
        // Must NOT be silently dropped — unsupported is the safe failure mode
        assertTrue("Must NOT be ActionApproved", result !is PolicyResult.ActionApproved)
    }

    // ── CameraCaptureTool.matches() — pure regex, no hardware ─────────────────

    @Test
    fun `'take a photo' matches rear camera`() {
        val input = CameraCaptureTool(ctx, capture).matches("take a photo")
        assertNotNull(input)
        assertEquals("rear", input!!.param("facing"))
    }

    @Test
    fun `'snap a photo' matches rear camera`() {
        val input = CameraCaptureTool(ctx, capture).matches("snap a photo")
        assertNotNull(input)
        assertEquals("rear", input!!.param("facing"))
    }

    @Test
    fun `'take a picture' matches rear camera`() {
        val input = CameraCaptureTool(ctx, capture).matches("take a picture")
        assertNotNull(input)
        assertEquals("rear", input!!.param("facing"))
    }

    @Test
    fun `'take a selfie' matches front camera`() {
        val input = CameraCaptureTool(ctx, capture).matches("take a selfie")
        assertNotNull(input)
        assertEquals("front", input!!.param("facing"))
    }

    @Test
    fun `'selfie' alone matches front camera`() {
        val input = CameraCaptureTool(ctx, capture).matches("selfie")
        assertNotNull(input)
        assertEquals("front", input!!.param("facing"))
    }

    @Test
    fun `'take a selfie' is NOT routed to rear`() {
        val input = CameraCaptureTool(ctx, capture).matches("take a selfie")
        assertEquals("front", input?.param("facing"))  // must be front, not rear
    }

    @Test
    fun `'play music' does not match camera tool`() {
        assertNull(CameraCaptureTool(ctx, capture).matches("play some music"))
    }

    @Test
    fun `'set a timer' does not match camera tool`() {
        assertNull(CameraCaptureTool(ctx, capture).matches("set a timer for 5 minutes"))
    }

    // ── AnalyzeCameraViewTool.matches() — pure regex, no hardware ────────────

    @Test
    fun `'what do you see' matches analyze tool`() {
        assertNotNull(AnalyzeCameraViewTool(ctx, capture, vision).matches("what do you see"))
    }

    @Test
    fun `'what can you see' matches analyze tool`() {
        assertNotNull(AnalyzeCameraViewTool(ctx, capture, vision).matches("what can you see"))
    }

    @Test
    fun `'look at this' matches analyze tool`() {
        assertNotNull(AnalyzeCameraViewTool(ctx, capture, vision).matches("look at this"))
    }

    @Test
    fun `'describe what you see' matches analyze tool`() {
        assertNotNull(AnalyzeCameraViewTool(ctx, capture, vision).matches("describe what you see"))
    }

    @Test
    fun `'analyze this' matches analyze tool`() {
        assertNotNull(AnalyzeCameraViewTool(ctx, capture, vision).matches("analyze this"))
    }

    @Test
    fun `'set a reminder' does not match analyze tool`() {
        assertNull(AnalyzeCameraViewTool(ctx, capture, vision).matches("set a reminder for 3pm"))
    }

    @Test
    fun `'take a photo' does not match analyze tool`() {
        // capture tool should win this, not analyze
        assertNull(AnalyzeCameraViewTool(ctx, capture, vision).matches("take a photo"))
    }
}
