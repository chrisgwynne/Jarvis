package com.jarvis.assistant.speaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerPermissionPolicyTest {

    private val highResult = SpeakerIdentityResult(
        confidence  = 0.95f,
        personId    = 1L,
        displayName = "Chris",
        band        = SpeakerIdentityResult.ConfidenceBand.HIGH_CONFIDENCE_MATCH
    )

    private val lowResult = SpeakerIdentityResult(
        confidence  = 0.70f,
        personId    = 2L,
        displayName = "Unknown",
        band        = SpeakerIdentityResult.ConfidenceBand.LOW_CONFIDENCE_OR_AMBIGUOUS
    )

    private val unknownResult = SpeakerIdentityResult.UNAVAILABLE

    // ── Public tools are always allowed ──────────────────────────────────────

    @Test fun `flashlight is allowed for unknown speaker`() {
        assertTrue(SpeakerPermissionPolicy.isAllowed(unknownResult, "flashlight"))
    }

    @Test fun `alarm is allowed for low confidence speaker`() {
        assertTrue(SpeakerPermissionPolicy.isAllowed(lowResult, "alarm"))
    }

    @Test fun `web_search is allowed for any speaker`() {
        for (result in listOf(highResult, lowResult, unknownResult)) {
            assertTrue(SpeakerPermissionPolicy.isAllowed(result, "web_search"))
        }
    }

    // ── Personal tools require HIGH_CONFIDENCE_MATCH ─────────────────────────

    @Test fun `shopping_list allowed for high confidence`() {
        assertTrue(SpeakerPermissionPolicy.isAllowed(highResult, "shopping_list"))
    }

    @Test fun `shopping_list denied for low confidence with reason`() {
        val decision = SpeakerPermissionPolicy.evaluate(lowResult, "shopping_list")
        assertFalse(decision.allowed)
        assertNotNull(decision.denyReason)
    }

    @Test fun `shopping_list denied for unknown speaker with reason`() {
        val decision = SpeakerPermissionPolicy.evaluate(unknownResult, "shopping_list")
        assertFalse(decision.allowed)
        assertNotNull(decision.denyReason)
    }

    @Test fun `memory_recall denied for unknown speaker`() {
        assertFalse(SpeakerPermissionPolicy.isAllowed(unknownResult, "memory_recall"))
    }

    @Test fun `call allowed for high confidence`() {
        assertTrue(SpeakerPermissionPolicy.isAllowed(highResult, "call"))
    }

    // ── Unknown tool names default to PERSONAL ────────────────────────────────

    @Test fun `unrecognised tool is treated as personal`() {
        assertEquals(SpeakerPermissionPolicy.ActionClass.PERSONAL,
            SpeakerPermissionPolicy.classifyAction("some_future_tool"))
    }

    @Test fun `unrecognised tool denied for unknown speaker`() {
        assertFalse(SpeakerPermissionPolicy.isAllowed(unknownResult, "some_future_tool"))
    }

    // ── Approved decision has no denyReason ───────────────────────────────────

    @Test fun `allowed decision carries no deny reason`() {
        val decision = SpeakerPermissionPolicy.evaluate(highResult, "shopping_list")
        assertTrue(decision.allowed)
        assertNull(decision.denyReason)
    }
}
