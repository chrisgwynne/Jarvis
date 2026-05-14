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
 * Covers the explicit-intent / confidence-promotion path added to
 * [LocalFirstRouter] to fix the WhatsApp "30-second thinking" bug.
 */
class LocalFirstRouterExplicitIntentTest {

    private lateinit var registry: ToolRegistry
    private lateinit var router:   LocalFirstRouter

    @Before fun setUp() {
        registry = mock()
        router   = LocalFirstRouter(registry)
    }

    private fun whatsappTool(): Tool = mock<Tool>().also {
        whenever(it.name).thenReturn("whatsapp_message")
        whenever(it.riskClass).thenReturn(RiskClass.LOW)
    }
    private fun smsTool(): Tool = mock<Tool>().also {
        whenever(it.name).thenReturn("send_sms")
        whenever(it.riskClass).thenReturn(RiskClass.LOW)
    }
    private fun lightsTool(): Tool = mock<Tool>().also {
        whenever(it.name).thenReturn("smart_home")
        whenever(it.riskClass).thenReturn(RiskClass.LOW)
    }
    private fun riskyTool(): Tool = mock<Tool>().also {
        whenever(it.name).thenReturn("send_sms")
        whenever(it.riskClass).thenReturn(RiskClass.HIGH)
    }

    @Test fun `whatsapp explicit channel + name + body promotes LOW tier to Local`() {
        val tool  = whatsappTool()
        val input = ToolInput("send a whatsapp to mike saying hello",
            mapOf("name" to "Mike", "message" to "hello"))
        val r = router.route(
            transcript        = "send a whatsapp to mike saying hello",
            isOnline          = true,
            precomputedMatch  = tool to input,
            tier              = TranscriptCorrector.ConfidenceTier.LOW
        )
        assertTrue("Expected Local got $r", r is LocalFirstRouter.RouteOutcome.Local)
        val l = r as LocalFirstRouter.RouteOutcome.Local
        assertEquals(TranscriptCorrector.ConfidenceTier.HIGH, l.tier)
    }

    @Test fun `whatsapp explicit channel + name + body at MEDIUM tier is also Local`() {
        val tool  = whatsappTool()
        val input = ToolInput("send a whatsapp to mike saying hello",
            mapOf("name" to "Mike", "message" to "hello"))
        val r = router.route(
            transcript        = "send a whatsapp to mike saying hello",
            isOnline          = true,
            precomputedMatch  = tool to input,
            tier              = TranscriptCorrector.ConfidenceTier.MEDIUM
        )
        assertTrue("Expected Local got $r", r is LocalFirstRouter.RouteOutcome.Local)
    }

    @Test fun `whatsapp missing body falls back to normal tier-handling at LOW tier`() {
        val tool  = whatsappTool()
        // No "message" param — no explicit-intent promotion.  whatsapp is
        // RiskClass.LOW so the new rule still routes it Local at LOW tier.
        val input = ToolInput("whatsapp mike", mapOf("name" to "Mike", "message" to ""))
        val r = router.route(
            transcript        = "whatsapp mike",
            isOnline          = true,
            precomputedMatch  = tool to input,
            tier              = TranscriptCorrector.ConfidenceTier.LOW
        )
        // RiskClass.LOW + LOW tier → Local (not Clarify).
        assertTrue("Expected Local for low-risk tool, got $r",
            r is LocalFirstRouter.RouteOutcome.Local)
    }

    @Test fun `HIGH-risk tool missing body at LOW tier routes Local — dispatcher handles confirmation`() {
        // Router contract change: confirmation is exclusively ToolDispatcher's
        // job.  Router always Local-routes a matched tool; risk × tier
        // decision happens inside ToolDispatcher.ConfirmationGate.
        val tool  = riskyTool()
        val input = ToolInput("send mike a text",
            mapOf("name" to "Mike", "message" to ""))
        val r = router.route(
            transcript        = "send mike a text",
            isOnline          = true,
            precomputedMatch  = tool to input,
            tier              = TranscriptCorrector.ConfidenceTier.LOW
        )
        assertTrue("Expected Local, got $r", r is LocalFirstRouter.RouteOutcome.Local)
    }

    @Test fun `smart_home at LOW tier executes locally (was the 30s thinking bug)`() {
        val tool  = lightsTool()
        val input = ToolInput("turn on the kitchen lights",
            mapOf("entity_name" to "kitchen lights", "action" to "on"))
        val r = router.route(
            transcript        = "turn on the kitchen lights",
            isOnline          = true,
            precomputedMatch  = tool to input,
            tier              = TranscriptCorrector.ConfidenceTier.LOW
        )
        assertTrue("Expected Local, got $r", r is LocalFirstRouter.RouteOutcome.Local)
    }

    @Test fun `null precomputed match still falls back to OpenClaw`() {
        whenever(registry.match(any<String>(), any<Boolean>())).thenReturn(null)
        val r = router.route(
            transcript        = "tell me a joke",
            isOnline          = true,
            precomputedMatch  = null,
            tier              = TranscriptCorrector.ConfidenceTier.HIGH
        )
        assertTrue(r is LocalFirstRouter.RouteOutcome.OpenClawFallback)
    }

    @Test fun `verify registry is not double-matched when precomputed provided`() {
        val tool  = whatsappTool()
        val input = ToolInput("whatsapp mike hello",
            mapOf("name" to "Mike", "message" to "hello"))
        router.route(
            transcript        = "whatsapp mike hello",
            isOnline          = true,
            precomputedMatch  = tool to input,
            tier              = TranscriptCorrector.ConfidenceTier.LOW
        )
        verify(registry, never()).match(any<String>(), any<Boolean>())
    }

    @Test fun `isExplicitIntent returns false for non-messaging tools`() {
        val nonMsg: Tool = mock<Tool>().also {
            whenever(it.name).thenReturn("flashlight")
            whenever(it.riskClass).thenReturn(RiskClass.LOW)
        }
        val input = ToolInput("torch on",
            mapOf("name" to "x", "message" to "y"))  // even if slots are present
        val result = router.isExplicitIntent("torch on", nonMsg, input)
        assertEquals(false, result)
    }
}
