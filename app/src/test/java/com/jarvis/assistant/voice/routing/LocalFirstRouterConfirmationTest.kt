package com.jarvis.assistant.voice.routing

import com.jarvis.assistant.audio.stt.TranscriptCorrector
import com.jarvis.assistant.tools.framework.RiskClass
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Confirms the LocalFirstRouter never emits Clarify anymore — confirmation
 * is exclusively owned by [com.jarvis.assistant.core.safety.ConfirmationGate]
 * inside ToolDispatcher, where risk × confidence tier is properly enforced.
 */
class LocalFirstRouterConfirmationTest {

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

    @Test fun `LOW-risk tool at LOW tier routes Local (no Clarify)`() {
        val r = router.route(
            transcript        = "take a photo",
            isOnline          = true,
            precomputedMatch  = mockTool("camera_capture", RiskClass.LOW) to ToolInput("take a photo"),
            tier              = TranscriptCorrector.ConfidenceTier.LOW
        )
        assertTrue("Expected Local, got $r", r is LocalFirstRouter.RouteOutcome.Local)
    }

    @Test fun `MEDIUM-risk tool at MEDIUM tier still routes Local — dispatcher handles confirmation`() {
        val r = router.route(
            transcript        = "send a message",
            isOnline          = true,
            precomputedMatch  = mockTool("send_sms", RiskClass.MEDIUM) to
                ToolInput("send a message", mapOf("name" to "Mike", "message" to "")),
            tier              = TranscriptCorrector.ConfidenceTier.MEDIUM
        )
        assertTrue("Expected Local (dispatcher decides), got $r",
            r is LocalFirstRouter.RouteOutcome.Local)
    }

    @Test fun `HIGH-risk tool at LOW tier routes Local — dispatcher decides Confirmation`() {
        val r = router.route(
            transcript        = "unlock front door",
            isOnline          = true,
            precomputedMatch  = mockTool("smart_home", RiskClass.HIGH) to ToolInput("unlock front door"),
            tier              = TranscriptCorrector.ConfidenceTier.LOW
        )
        // Router no longer emits Clarify itself — ToolDispatcher.ConfirmationGate
        // will register the pending action and return NeedsConfirmation.
        assertTrue("Expected Local (no in-router Clarify), got $r",
            r is LocalFirstRouter.RouteOutcome.Local)
    }
}
