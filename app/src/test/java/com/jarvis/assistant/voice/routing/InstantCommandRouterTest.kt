package com.jarvis.assistant.voice.routing

import com.jarvis.assistant.tools.framework.RiskClass
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolRegistry
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstantCommandRouterTest {

    /**
     * Stub Tool that matches against a fixed regex and reports the given
     * name.  Tests use a stub-only registry so we never depend on real
     * Android dependencies (Context, ContactLookup, …).
     */
    private class FakeTool(
        override val name: String,
        private val pattern: Regex,
    ) : Tool {
        override val description = "fake $name"
        override val requiresNetwork = false
        override val isLocalFallback = true
        override val requiredPermissions = emptyList<String>()
        override val riskClass = RiskClass.LOW

        override fun matches(transcript: String): ToolInput? =
            if (pattern.containsMatchIn(transcript)) ToolInput(transcript, emptyMap()) else null

        override fun schema() = ToolSchema(name, "fake")
        override suspend fun execute(input: ToolInput): ToolResult =
            ToolResult.Success("ok")
    }

    private fun registry() = ToolRegistry(
        tools = listOf(
            FakeTool("time",             Regex("""(?i)\bwhat is the time\b|\bwhat time is it\b""")),
            FakeTool("battery",          Regex("""(?i)\bbattery\b""")),
            FakeTool("where_am_i",       Regex("""(?i)\bwhere am i\b""")),
            FakeTool("call_contact",     Regex("""(?i)\bcall\s+\w+""")),
            FakeTool("whatsapp_message", Regex("""(?i)\bsend\s+whatsapp\s+to\s+\w+""")),
            FakeTool("send_sms",         Regex("""(?i)\bsend\s+(?:text|sms)\s+to\s+\w+""")),
            FakeTool("open_app",         Regex("""(?i)\bopen\s+\w+""")),
            FakeTool("set_timer",        Regex("""(?i)\bset\s+(?:a\s+)?timer\s+for\b""")),
            FakeTool("set_alarm",        Regex("""(?i)\bset\s+(?:an\s+)?alarm\b""")),
            FakeTool("flashlight",       Regex("""(?i)\b(?:torch|flashlight)\b""")),
            FakeTool("volume_control",   Regex("""(?i)\bvolume\b""")),
            FakeTool("media_control",    Regex("""(?i)\b(?:pause|resume|skip)\b""")),
            FakeTool("camera_capture",   Regex("""(?i)\btake\s+(?:a\s+)?(?:photo|selfie|picture)\b""")),
            FakeTool("smart_home",       Regex("""(?i)\bturn\s+(?:on|off)\b""")),
            FakeTool("calendar",         Regex("""(?i)\b(?:what'?s on|what is on)\s+my\s+calendar\b""")),
            FakeTool("end_call",         Regex("""(?i)\b(?:end|hang up)\s+call\b""")),
            // Non-instant tools — these MUST NOT short-circuit OpenClaw.
            FakeTool("web_search",       Regex("""(?i)\bsearch\b""")),
            FakeTool("weather",          Regex("""(?i)\bweather\b""")),
        )
    )

    private fun router() = InstantCommandRouter(registry())

    // ── Required regression cases ──────────────────────────────────────────

    @Test
    fun `'What time is it slash' routes to TIME locally`() {
        // The slash from STT is stripped by the normalizer, "whats"
        // becomes "what is", and the time tool catches it.  No OpenClaw
        // tool present in this registry — and even if it were, the
        // intent label proves the local fast-path took precedence.
        val r = router().route("What time is it/", isOnline = true)
        assertTrue(r is InstantCommandRouter.InstantRouteResult.Match)
        r as InstantCommandRouter.InstantRouteResult.Match
        assertEquals("TIME", r.intent)
        assertEquals("time", r.tool.name)
    }

    @Test
    fun `'send a whatsapp to Mike saying hello' routes to SEND_MESSAGE`() {
        // The phrase rewrite "send a whatsapp" → "send whatsapp" is what
        // makes this fire.  Channel is WhatsApp per the registered tool.
        val r = router().route("send a whatsapp to Mike saying hello", isOnline = true)
        assertTrue(r is InstantCommandRouter.InstantRouteResult.Match)
        r as InstantCommandRouter.InstantRouteResult.Match
        assertEquals("SEND_MESSAGE", r.intent)
        assertEquals("whatsapp_message", r.tool.name)
    }

    @Test
    fun `'call Mike' routes to CALL`() {
        val r = router().route("call Mike", isOnline = true)
        assertTrue(r is InstantCommandRouter.InstantRouteResult.Match)
        assertEquals("CALL",
            (r as InstantCommandRouter.InstantRouteResult.Match).intent)
    }

    @Test
    fun `'open Spotify' routes to OPEN_APP`() {
        val r = router().route("open Spotify", isOnline = true)
        assertTrue(r is InstantCommandRouter.InstantRouteResult.Match)
        assertEquals("OPEN_APP",
            (r as InstantCommandRouter.InstantRouteResult.Match).intent)
    }

    @Test
    fun `'turn on kitchen light' routes to HOME_ASSISTANT_DEVICE`() {
        val r = router().route("turn on kitchen light", isOnline = true)
        assertTrue(r is InstantCommandRouter.InstantRouteResult.Match)
        assertEquals("HOME_ASSISTANT_DEVICE",
            (r as InstantCommandRouter.InstantRouteResult.Match).intent)
    }

    @Test
    fun `'switch on the kitchen light' is rewritten and routes the same way`() {
        // "switch on" → "turn on" via PHRASE_REWRITES, "turn the" → "turn".
        val r = router().route("switch on the kitchen light", isOnline = true)
        assertTrue(r is InstantCommandRouter.InstantRouteResult.Match)
        assertEquals("HOME_ASSISTANT_DEVICE",
            (r as InstantCommandRouter.InstantRouteResult.Match).intent)
    }

    @Test
    fun `'where am I' routes to LOCATION`() {
        val r = router().route("where am I", isOnline = true)
        assertTrue(r is InstantCommandRouter.InstantRouteResult.Match)
        assertEquals("LOCATION",
            (r as InstantCommandRouter.InstantRouteResult.Match).intent)
    }

    @Test
    fun `'help me price my Etsy listings' falls through to deeper brain`() {
        // No INSTANT_TOOL_INTENTS allowlist tool matches — caller will
        // escalate to OpenClaw / LLM.
        val r = router().route("help me price my Etsy listings", isOnline = true)
        assertTrue("expected NoMatch, got $r",
            r is InstantCommandRouter.InstantRouteResult.NoMatch)
    }

    @Test
    fun `non-instant tool match still returns NoMatch`() {
        // 'web_search' isn't in the instant allowlist.  Even though the
        // registry would match it, the router declines to short-circuit
        // so OpenClaw / LLM still get a chance.
        val r = router().route("search the web for something", isOnline = true)
        assertTrue(r is InstantCommandRouter.InstantRouteResult.NoMatch)
        val nm = r as InstantCommandRouter.InstantRouteResult.NoMatch
        assertTrue("reason should mention tool_not_instant: ${nm.reason}",
            nm.reason.startsWith("tool_not_instant"))
    }

    @Test
    fun `blank transcript is NoMatch`() {
        val r = router().route("   ", isOnline = true)
        assertTrue(r is InstantCommandRouter.InstantRouteResult.NoMatch)
    }

    @Test
    fun `set a timer for 5 minutes routes to TIMER`() {
        val r = router().route("set a timer for 5 minutes", isOnline = true)
        assertTrue(r is InstantCommandRouter.InstantRouteResult.Match)
        assertEquals("TIMER",
            (r as InstantCommandRouter.InstantRouteResult.Match).intent)
    }

    @Test
    fun `'whats my battery' contraction is normalised then matched`() {
        val r = router().route("whats my battery", isOnline = true)
        assertTrue(r is InstantCommandRouter.InstantRouteResult.Match)
        assertEquals("BATTERY",
            (r as InstantCommandRouter.InstantRouteResult.Match).intent)
    }

    @Test
    fun `normalised transcript is reported back on Match`() {
        val r = router().route("What time is it/", isOnline = true)
        r as InstantCommandRouter.InstantRouteResult.Match
        // Normalised form: lowercase, slash stripped, "whats" expanded.
        assertEquals("what time is it", r.normalisedTranscript)
    }
}
