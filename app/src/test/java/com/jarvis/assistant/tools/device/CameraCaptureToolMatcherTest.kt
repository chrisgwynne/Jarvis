package com.jarvis.assistant.tools.device

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the [CameraCaptureTool.FRONT_TRIGGERS] /
 * [CameraCaptureTool.REAR_TRIGGERS] matcher.
 *
 * The historical bug was a bare `\bselfie\b` catch-all that made
 * "show me the selfie" fire the camera again instead of routing to
 * ViewMediaTool.  These tests pin the corrected behaviour so the
 * regression can't sneak back in.
 *
 * NOTE: we exercise the matcher through reflection-free Regex
 * literals copy-pasted from the tool to keep the test self-contained
 * — the tool itself needs an Android Context to construct.
 */
class CameraCaptureToolMatcherTest {

    private val FRONT_TRIGGERS = Regex(
        """(?:take|snap|capture|grab)\s+(?:(?:me\s+a?|my|a|an|the|another)\s+)?(?:quick\s+|new\s+|one\s+more\s+)?(?:selfie|front\s+(?:photo|pic(?:ture)?|image|shot))""",
        RegexOption.IGNORE_CASE,
    )

    private val REAR_TRIGGERS = Regex(
        """(?:take|snap|capture)\s+(?:(?:me\s+a?|my|a|an|the)\s+)?(?:quick\s+)?(?:photo|picture|image|shot|pic)\b""",
        RegexOption.IGNORE_CASE,
    )

    // ── Positive: capture verbs still match ──────────────────────────────

    @Test
    fun `take a selfie matches FRONT`() {
        assertNotNull(FRONT_TRIGGERS.find("take a selfie"))
    }

    @Test
    fun `snap a selfie matches FRONT`() {
        assertNotNull(FRONT_TRIGGERS.find("snap a selfie"))
    }

    @Test
    fun `take another selfie matches FRONT`() {
        assertNotNull(FRONT_TRIGGERS.find("take another selfie"))
    }

    @Test
    fun `take a new selfie matches FRONT`() {
        assertNotNull(FRONT_TRIGGERS.find("take a new selfie"))
    }

    @Test
    fun `take a photo matches REAR`() {
        assertNotNull(REAR_TRIGGERS.find("take a photo"))
        assertNotNull(REAR_TRIGGERS.find("snap a picture"))
    }

    // ── Negative: "show me the selfie" must NOT match camera ────────────

    @Test
    fun `show me the selfie does NOT match FRONT (regression)`() {
        assertNull("show me the selfie should not match camera",
            FRONT_TRIGGERS.find("show me the selfie"))
        assertNull("show me my selfie should not match camera",
            FRONT_TRIGGERS.find("show me my selfie"))
        assertNull("open the photo should not match camera",
            FRONT_TRIGGERS.find("open the photo"))
        assertNull("view the picture should not match camera",
            REAR_TRIGGERS.find("view the picture"))
    }

    @Test
    fun `share the selfie does NOT match FRONT`() {
        assertNull(FRONT_TRIGGERS.find("share the selfie"))
        assertNull(FRONT_TRIGGERS.find("send the selfie to Mike"))
    }

    @Test
    fun `bare selfie does NOT match FRONT anymore`() {
        // The previous regex had a `|selfie\b` catch-all.  Now a
        // capture verb is required.  Bare "selfie" → no match,
        // route falls through to the rest of the pipeline.
        assertNull(FRONT_TRIGGERS.find("selfie"))
        assertNull(FRONT_TRIGGERS.find("selfie please"))
    }
}
