package com.jarvis.assistant.voice.routing

import com.jarvis.assistant.audio.stt.TranscriptCorrector
import com.jarvis.assistant.tools.framework.RiskClass
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests for the Tier-A3/A4 routing rules.  The router never re-runs the
 * registry's regex sweep when the caller passes a precomputed match —
 * this is verified by the `never()` invocation on a mock registry.
 */
class LocalFirstRouterTest {

    private lateinit var registry: ToolRegistry
    private lateinit var router:   LocalFirstRouter

    @Before fun setUp() {
        registry = mock()
        router   = LocalFirstRouter(registry)
    }

    private fun mockTool(name: String, risk: RiskClass): Tool {
        val t: Tool = mock()
        whenever(t.name).thenReturn(name)
        whenever(t.riskClass).thenReturn(risk)
        return t
    }
    private fun mockInput(transcript: String, params: Map<String, String> = emptyMap()) =
        ToolInput(transcript, params)

    @Test fun `HIGH tier with low-risk tool routes Local and skips registry match`() {
        val tool  = mockTool("smart_home", RiskClass.LOW)
        val input = mockInput("turn on kitchen lights")
        val r = router.route(
            transcript        = "turn on kitchen lights",
            isOnline          = true,
            precomputedMatch  = tool to input,
            tier              = TranscriptCorrector.ConfidenceTier.HIGH
        )
        assertTrue("Expected Local, got $r", r is LocalFirstRouter.RouteOutcome.Local)
        assertEquals(tool, (r as LocalFirstRouter.RouteOutcome.Local).tool)
        verify(registry, never()).match(any(), any())
    }

    @Test fun `MEDIUM tier HIGH-risk tool routes Local — dispatcher confirms`() {
        // Router no longer emits Clarify; confirmation is exclusively the
        // ToolDispatcher.ConfirmationGate's job, which sees risk+tier.
        val tool  = mockTool("delete_routine", RiskClass.HIGH)
        val input = mockInput("delete the morning routine", mapOf("name" to "morning"))
        val r = router.route(
            transcript        = "delete the morning routine",
            isOnline          = true,
            precomputedMatch  = tool to input,
            tier              = TranscriptCorrector.ConfidenceTier.MEDIUM
        )
        assertTrue("Expected Local, got $r", r is LocalFirstRouter.RouteOutcome.Local)
    }

    @Test fun `MEDIUM tier with LOW-risk tool routes Local (safe to auto-execute)`() {
        val tool  = mockTool("flashlight", RiskClass.LOW)
        val input = mockInput("torch on")
        val r = router.route(
            transcript        = "torch on",
            isOnline          = true,
            precomputedMatch  = tool to input,
            tier              = TranscriptCorrector.ConfidenceTier.MEDIUM
        )
        assertTrue("Expected Local, got $r", r is LocalFirstRouter.RouteOutcome.Local)
    }

    @Test fun `LOW tier HIGH-risk tool routes Local — dispatcher confirms`() {
        // Same contract as the MEDIUM-tier case: the router always Local-routes
        // matched tools; ToolDispatcher.ConfirmationGate decides whether to
        // prompt based on (riskClass, confidenceTier).
        val tool  = mockTool("delete_routine", RiskClass.HIGH)
        val input = mockInput("kill it")
        val r = router.route(
            transcript        = "kill it",
            isOnline          = true,
            precomputedMatch  = tool to input,
            tier              = TranscriptCorrector.ConfidenceTier.LOW
        )
        assertTrue("Expected Local, got $r", r is LocalFirstRouter.RouteOutcome.Local)
    }

    @Test fun `null precomputed match falls back to OpenClaw without crashing`() {
        whenever(registry.match(any<String>(), any<Boolean>())).thenReturn(null)
        val r = router.route(
            transcript        = "what is the airspeed velocity of an unladen swallow",
            isOnline          = true,
            precomputedMatch  = null,
            tier              = TranscriptCorrector.ConfidenceTier.HIGH
        )
        assertTrue("Expected OpenClawFallback, got $r",
            r is LocalFirstRouter.RouteOutcome.OpenClawFallback)
    }

}
