package com.jarvis.assistant.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ActionPolicyGate] and [PolicyAuditLog].
 *
 * Coverage:
 *  - Known tool names are approved and mapped to the correct [ActionType]
 *  - Every tool name registered in [ActionType.APPROVED_TOOL_MAP] produces [PolicyResult.ActionApproved]
 *  - Unknown tool names produce [PolicyResult.ActionUnsupported] (not silently dropped)
 *  - Unsupported results are recorded in [PolicyAuditLog]
 *  - Blank transcripts produce [PolicyResult.ActionMalformed] with EMPTY_TRANSCRIPT reason
 *  - Transcripts exceeding 1000 characters produce [PolicyResult.ActionUnsafe]
 *  - Clean transcripts return null from [ActionPolicyGate.validateTranscript]
 *  - [ActionPolicyGate.isApproved] returns correct boolean for known/unknown tool names
 *  - Approved actions are recorded in the audit log
 *  - [PolicyResult.ActionDenied] can be constructed, recorded, and retrieved correctly
 */
class ActionPolicyGateTest {

    @Before
    fun setUp() {
        PolicyAuditLog.clearForTest()
    }

    // ── Test 1: Known tool name returns ActionApproved ────────────────────────

    @Test
    fun `approved action — known tool name returns ActionApproved`() {
        val result = ActionPolicyGate.evaluate("call_contact", "call mum")

        assertTrue(
            "Expected ActionApproved but got ${result::class.simpleName}",
            result is PolicyResult.ActionApproved
        )
        val approved = result as PolicyResult.ActionApproved
        assertEquals(ActionType.CALL_CONTACT, approved.requestedActionType)
        assertEquals("call_contact", approved.toolName)
    }

    // ── Test 2: All registered tool names return ActionApproved ───────────────

    @Test
    fun `approved action — all registered tool names return ActionApproved`() {
        val failedTools = mutableListOf<String>()

        for (toolName in ActionType.APPROVED_TOOL_MAP.keys) {
            PolicyAuditLog.clearForTest()
            val result = ActionPolicyGate.evaluate(toolName, "test")
            if (result !is PolicyResult.ActionApproved) {
                failedTools.add(toolName)
            }
        }

        assertTrue(
            "These registered tools did not return ActionApproved: $failedTools",
            failedTools.isEmpty()
        )
    }

    // ── Test 3: Unknown tool name returns ActionUnsupported (not dropped) ─────

    @Test
    fun `unsupported action — unknown tool name returns ActionUnsupported NOT silently dropped`() {
        val result = ActionPolicyGate.evaluate("some_unknown_tool", "do something dangerous")

        assertTrue(
            "Expected ActionUnsupported but got ${result::class.simpleName}",
            result is PolicyResult.ActionUnsupported
        )
        val unsupported = result as PolicyResult.ActionUnsupported
        assertEquals("some_unknown_tool", unsupported.toolNameAttempted)
        assertEquals("do something dangerous", unsupported.rawRequestedAction)
        assertTrue(
            "humanMessage must not be blank for unsupported actions",
            unsupported.humanMessage.isNotBlank()
        )
    }

    // ── Test 4: Unsupported action is logged ──────────────────────────────────

    @Test
    fun `unsupported action is logged to audit log`() {
        PolicyAuditLog.clearForTest()
        ActionPolicyGate.evaluate("unknown_tool", "transcript")

        assertEquals(1, PolicyAuditLog.unsupportedCount())
    }

    // ── Test 5: Blank transcript returns ActionMalformed ─────────────────────

    @Test
    fun `malformed — blank transcript returns ActionMalformed`() {
        val emptyResult = ActionPolicyGate.validateTranscript("")
        val blankResult = ActionPolicyGate.validateTranscript("   ")

        assertNotNull("Empty transcript should not pass validation", emptyResult)
        assertNotNull("Whitespace-only transcript should not pass validation", blankResult)

        assertTrue(
            "Empty transcript should produce ActionMalformed",
            emptyResult is PolicyResult.ActionMalformed
        )
        assertTrue(
            "Whitespace transcript should produce ActionMalformed",
            blankResult is PolicyResult.ActionMalformed
        )

        assertEquals(
            "EMPTY_TRANSCRIPT",
            (emptyResult as PolicyResult.ActionMalformed).reasonCode
        )
        assertEquals(
            "EMPTY_TRANSCRIPT",
            (blankResult as PolicyResult.ActionMalformed).reasonCode
        )
    }

    // ── Test 6: Transcript over 1000 chars returns ActionUnsafe ──────────────

    @Test
    fun `unsafe — transcript over 1000 chars returns ActionUnsafe`() {
        val longTranscript = "a".repeat(1001)
        val result = ActionPolicyGate.validateTranscript(longTranscript)

        assertNotNull("Oversized transcript should not pass validation", result)
        assertTrue(
            "Oversized transcript should produce ActionUnsafe but got ${result?.let { it::class.simpleName }}",
            result is PolicyResult.ActionUnsafe
        )
        assertEquals(
            "TRANSCRIPT_EXCEEDS_SAFE_LENGTH",
            (result as PolicyResult.ActionUnsafe).reasonCode
        )
    }

    // ── Test 7: Valid transcript returns null ─────────────────────────────────

    @Test
    fun `valid transcript returns null from validateTranscript`() {
        assertNull(
            "Short clean transcript should pass validation",
            ActionPolicyGate.validateTranscript("call mum")
        )
        assertNull(
            "Normal question should pass validation",
            ActionPolicyGate.validateTranscript("what's the weather")
        )
    }

    // ── Test 8: isApproved returns correct boolean ────────────────────────────

    @Test
    fun `isApproved returns true for known tools false for unknown`() {
        assertTrue(
            "flashlight should be an approved tool",
            ActionPolicyGate.isApproved("flashlight")
        )
        assertTrue(
            "send_sms should be an approved tool",
            ActionPolicyGate.isApproved("send_sms")
        )
        assertFalse(
            "hack_everything must not be an approved tool",
            ActionPolicyGate.isApproved("hack_everything")
        )
        assertFalse(
            "Empty string must not be an approved tool",
            ActionPolicyGate.isApproved("")
        )
    }

    // ── Test 9: Audit log records approved actions ────────────────────────────

    @Test
    fun `audit log records approved actions`() {
        PolicyAuditLog.clearForTest()
        ActionPolicyGate.evaluate("flashlight", "turn on torch")

        val entries = PolicyAuditLog.recentEntries()
        assertEquals(1, entries.size)
        assertTrue(
            "Audit entry should contain an ActionApproved result",
            entries.first().result is PolicyResult.ActionApproved
        )
    }

    // ── Test 10: ActionDenied can be constructed and logged correctly ─────────

    @Test
    fun `denied action — construct ActionDenied and verify structure`() {
        val denied = PolicyResult.ActionDenied(
            requestedActionType  = ActionType.CALL_CONTACT,
            rawRequestedAction   = "call nobody",
            reasonCode           = DenialReason.PERMISSION_DENIED,
            humanMessage         = "Permission required.",
            debugDetails         = "CALL_PHONE not granted"
        )

        PolicyAuditLog.record(denied)

        assertEquals(1, PolicyAuditLog.deniedCount())
        assertEquals("Permission required.", denied.humanMessage)
        assertEquals(DenialReason.PERMISSION_DENIED, denied.reasonCode)
        assertEquals(ActionType.CALL_CONTACT, denied.requestedActionType)
        assertEquals("call nobody", denied.rawRequestedAction)
    }
}
