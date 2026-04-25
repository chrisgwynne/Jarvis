package com.jarvis.assistant.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KeywordIntentRouterTest — pins the behaviour every downstream consumer
 * relies on: exact match coverage, fuzzy fallback, priority resolution,
 * modifier + label extraction, risk escalation and the envelope JSON
 * schema.  Each test names the rule it's defending so failures are
 * self-explanatory.
 */
class KeywordIntentRouterTest {

    // ── fixtures ─────────────────────────────────────────────────────────────

    /**
     * Hand-wired stub so tests can exercise every "this" priority slot
     * without touching Android framework classes.
     */
    private class StubSources(
        private var selected:   String? = null,
        private var input:      String? = null,
        private var clipboard:  String? = null,
        private var fg:         String? = null,
        private var url:        String? = null,
        private var screenshot: String? = null,
        private var uiEvent:    String? = null,
        private var prev:       String? = null,
    ) : ContextSources {
        override fun selectedText()         = selected
        override fun currentInputText()     = input
        override fun clipboardText()        = clipboard
        override fun foregroundApp()        = fg
        override fun visibleUrl()           = url
        override fun lastScreenshotPath()   = screenshot
        override fun lastUiEvent()          = uiEvent
        override fun previousTurnContext()  = prev
    }

    private fun router(sources: ContextSources = StubSources()) =
        KeywordIntentRouter(contextResolver = ContextResolver(sources))

    // ── exact-match coverage (one per keyword family) ───────────────────────

    @Test fun `exact match — look at this → OBSERVE_SCREEN`() {
        val env = router().route("look at this")!!
        assertEquals(PrimaryIntent.OBSERVE_SCREEN, env.primaryIntent)
    }

    @Test fun `exact match — check this → OBSERVE_SCREEN`() {
        assertEquals(PrimaryIntent.OBSERVE_SCREEN, router().route("check this")?.primaryIntent)
    }

    @Test fun `exact match — do this → ACT_ON_CONTEXT`() {
        assertEquals(PrimaryIntent.ACT_ON_CONTEXT, router().route("do this")?.primaryIntent)
    }

    @Test fun `exact match — handle this → ACT_ON_CONTEXT`() {
        assertEquals(PrimaryIntent.ACT_ON_CONTEXT, router().route("handle this")?.primaryIntent)
    }

    @Test fun `exact match — remember this → STORE_CONTEXT`() {
        assertEquals(PrimaryIntent.STORE_CONTEXT, router().route("remember this")?.primaryIntent)
    }

    @Test fun `exact match — save this → STORE_CONTEXT`() {
        assertEquals(PrimaryIntent.STORE_CONTEXT, router().route("save this")?.primaryIntent)
    }

    @Test fun `exact match — what was I doing → RECALL_RECENT_CONTEXT`() {
        assertEquals(PrimaryIntent.RECALL_RECENT_CONTEXT, router().route("what was I doing")?.primaryIntent)
    }

    @Test fun `exact match — what did that say → RECALL_RECENT_CONTEXT`() {
        assertEquals(PrimaryIntent.RECALL_RECENT_CONTEXT, router().route("what did that say")?.primaryIntent)
    }

    @Test fun `exact match — reply to this → DRAFT_REPLY`() {
        assertEquals(PrimaryIntent.DRAFT_REPLY, router().route("reply to this")?.primaryIntent)
    }

    @Test fun `exact match — say this better → DRAFT_REPLY + REWRITE_MODE`() {
        val env = router().route("say this better")!!
        assertEquals(PrimaryIntent.DRAFT_REPLY, env.primaryIntent)
        assertTrue(IntentModifier.REWRITE_MODE in env.modifiers)
    }

    @Test fun `exact match — stop → INTERRUPT_ASSISTANT`() {
        assertEquals(PrimaryIntent.INTERRUPT_ASSISTANT, router().route("stop")?.primaryIntent)
    }

    @Test fun `exact match — wait → PAUSE_ASSISTANT`() {
        assertEquals(PrimaryIntent.PAUSE_ASSISTANT, router().route("wait")?.primaryIntent)
    }

    @Test fun `exact match — carry on → RESUME_ASSISTANT`() {
        assertEquals(PrimaryIntent.RESUME_ASSISTANT, router().route("carry on")?.primaryIntent)
    }

    @Test fun `exact match — short answer → CHANGE_RESPONSE_STYLE + STYLE_CONCISE`() {
        val env = router().route("short answer")!!
        assertEquals(PrimaryIntent.CHANGE_RESPONSE_STYLE, env.primaryIntent)
        assertTrue(IntentModifier.STYLE_CONCISE in env.modifiers)
    }

    @Test fun `exact match — explain properly → CHANGE_RESPONSE_STYLE + STYLE_EXPANDED`() {
        val env = router().route("explain properly")!!
        assertEquals(PrimaryIntent.CHANGE_RESPONSE_STYLE, env.primaryIntent)
        assertTrue(IntentModifier.STYLE_EXPANDED in env.modifiers)
    }

    // ── fuzzy fallback ──────────────────────────────────────────────────────

    @Test fun `fuzzy fallback — please be quiet → INTERRUPT_ASSISTANT`() {
        // No exact pattern matches; fuzzy hits the "be quiet" phrase with
        // the "quiet" anchor boost, clearing the 0.55 threshold.
        val env = router().route("please be quiet")
        assertNotNull(env)
        assertEquals(PrimaryIntent.INTERRUPT_ASSISTANT, env!!.primaryIntent)
        assertTrue("fuzzy confidence must stay below the exact-match baseline",
            env.confidence < 0.95)
    }

    @Test fun `fuzzy fallback — go on → RESUME_ASSISTANT`() {
        val env = router().route("go on")
        assertNotNull(env)
        assertEquals(PrimaryIntent.RESUME_ASSISTANT, env!!.primaryIntent)
    }

    @Test fun `unrecognised chatter — returns null so caller can fall through`() {
        assertNull(router().route("what's the weather tomorrow"))
        assertNull(router().route(""))
        assertNull(router().route("   "))
    }

    // ── priority conflict resolution ────────────────────────────────────────

    @Test fun `priority — stop and look at this → INTERRUPT wins over OBSERVE`() {
        // Control signals (rank 0) must beat explicit observe (rank 4).
        val env = router().route("stop and look at this")!!
        assertEquals(PrimaryIntent.INTERRUPT_ASSISTANT, env.primaryIntent)
    }

    @Test fun `priority — do this and remember it → ACT_ON_CONTEXT wins over STORE_CONTEXT`() {
        // Even though the phrase also trips the "and remember it" modifier,
        // the underlying primary is the explicit action, not the memory op.
        val env = router().route("do this and remember it")!!
        assertEquals(PrimaryIntent.ACT_ON_CONTEXT, env.primaryIntent)
        assertTrue(IntentModifier.STORE_RESULT in env.modifiers)
    }

    // ── modifier extraction ─────────────────────────────────────────────────

    @Test fun `compose-on modifier — look at this and remember it → OBSERVE + STORE_RESULT`() {
        val env = router().route("look at this and remember it")!!
        assertEquals(PrimaryIntent.OBSERVE_SCREEN, env.primaryIntent)
        assertTrue(IntentModifier.STORE_RESULT in env.modifiers)
    }

    @Test fun `compose-on modifier — save variant — look at this and save it → STORE_RESULT`() {
        val env = router().route("look at this and save it")!!
        assertTrue(IntentModifier.STORE_RESULT in env.modifiers)
    }

    // ── label extraction ────────────────────────────────────────────────────

    @Test fun `label — save this as invoice 114 → STORE_CONTEXT + LABEL_PROVIDED`() {
        val env = router().route("save this as invoice 114")!!
        assertEquals(PrimaryIntent.STORE_CONTEXT, env.primaryIntent)
        assertEquals("invoice 114", env.label)
        assertTrue(IntentModifier.LABEL_PROVIDED in env.modifiers)
    }

    @Test fun `label — bare save this has no label`() {
        val env = router().route("save this")!!
        assertEquals(PrimaryIntent.STORE_CONTEXT, env.primaryIntent)
        assertNull(env.label)
        assertFalse(IntentModifier.LABEL_PROVIDED in env.modifiers)
    }

    // ── risk + confirmation ────────────────────────────────────────────────

    @Test fun `risk — reply to this and send it → HIGH + confirm`() {
        val env = router().route("reply to this and send it")!!
        assertEquals(PrimaryIntent.DRAFT_REPLY, env.primaryIntent)
        assertEquals(RiskLevel.HIGH, env.riskLevel)
        assertTrue(env.requiresConfirmation)
    }

    @Test fun `risk — do this defaults to MEDIUM + confirm`() {
        val env = router().route("do this")!!
        assertEquals(PrimaryIntent.ACT_ON_CONTEXT, env.primaryIntent)
        assertEquals(RiskLevel.MEDIUM, env.riskLevel)
        assertTrue(env.requiresConfirmation)
    }

    @Test fun `risk — control signal stop is LOW and never confirms`() {
        val env = router().route("stop")!!
        assertEquals(RiskLevel.LOW, env.riskLevel)
        assertFalse(env.requiresConfirmation)
    }

    @Test fun `risk — look at this is LOW and never confirms`() {
        val env = router().route("look at this")!!
        assertEquals(RiskLevel.LOW, env.riskLevel)
        assertFalse(env.requiresConfirmation)
    }

    @Test fun `risk — input-field text containing a high-risk verb escalates`() {
        // User says "reply to this", but their current draft already says
        // "transfer" — scanning the input field must bump the risk.
        val env = router(StubSources(input = "transfer £500 to Mum")).route("reply to this")!!
        assertEquals(RiskLevel.HIGH, env.riskLevel)
        assertTrue(env.requiresConfirmation)
    }

    // ── "this" resolution priority ─────────────────────────────────────────

    @Test fun `this-resolution — selected_text beats clipboard`() {
        val sources = StubSources(selected = "SEL", clipboard = "CLIP")
        val resolver = ContextResolver(sources)
        val ref = resolver.resolveReferent(resolver.snapshot())
        assertEquals("selected_text", ref.source)
        assertEquals("SEL", ref.text)
    }

    @Test fun `this-resolution — current_input beats clipboard when no selection`() {
        val sources = StubSources(input = "DRAFT", clipboard = "CLIP")
        val resolver = ContextResolver(sources)
        val ref = resolver.resolveReferent(resolver.snapshot())
        assertEquals("current_input_text", ref.source)
        assertEquals("DRAFT", ref.text)
    }

    @Test fun `this-resolution — falls back to previous turn when nothing else populated`() {
        val sources = StubSources(prev = "the Gmail thread")
        val resolver = ContextResolver(sources)
        val ref = resolver.resolveReferent(resolver.snapshot())
        assertEquals("previous_turn", ref.source)
        assertEquals("the Gmail thread", ref.text)
    }

    @Test fun `this-resolution — none when every slot is blank`() {
        val resolver = ContextResolver(StubSources())
        val ref = resolver.resolveReferent(resolver.snapshot())
        assertEquals("none", ref.source)
        assertNull(ref.text)
    }

    @Test fun `this-resolution — blank strings are treated as null`() {
        val sources = StubSources(selected = "   ", input = "DRAFT")
        val resolver = ContextResolver(sources)
        val ref = resolver.resolveReferent(resolver.snapshot())
        assertEquals("current_input_text", ref.source)
    }

    // ── context snapshot exposed on envelope ────────────────────────────────

    @Test fun `envelope carries a populated ResolvedContext snapshot`() {
        val sources = StubSources(
            selected   = "Hello",
            clipboard  = "https://example.com/thread/42",
            fg         = "com.google.android.gm",
            url        = "https://mail.google.com/mail/u/0/#inbox",
            screenshot = "/data/data/com.jarvis/files/screen_observations/9.png",
        )
        val env = router(sources).route("look at this")!!
        assertEquals("Hello", env.resolvedContext.selectedText)
        assertEquals("com.google.android.gm", env.resolvedContext.foregroundApp)
        assertEquals("https://mail.google.com/mail/u/0/#inbox", env.resolvedContext.visibleUrl)
    }

    // ── envelope JSON schema ────────────────────────────────────────────────

    @Test fun `envelope toJson produces the documented schema keys`() {
        val sources = StubSources(selected = "Hello", fg = "com.example")
        val env = router(sources).route("save this as invoice 114")!!
        val json = env.toJson()

        // Top-level keys
        assertEquals("save this as invoice 114", json.getString("raw_text"))
        assertEquals("STORE_CONTEXT",            json.getString("primary_intent"))
        assertEquals("invoice 114",              json.getString("label"))
        assertTrue(json.has("modifiers"))
        assertTrue(json.has("confidence"))
        assertTrue(json.has("resolved_context"))
        assertEquals("low",                      json.getString("risk_level"))
        assertFalse(json.getBoolean("requires_confirmation"))

        // Modifier serialisation — LABEL_PROVIDED must round-trip.
        val mods = json.getJSONArray("modifiers")
        val modList = (0 until mods.length()).map { mods.getString(it) }
        assertTrue("LABEL_PROVIDED" in modList)

        // Nested resolved_context schema — every slot present, nulls stay null.
        val ctx = json.getJSONObject("resolved_context")
        assertEquals("Hello", ctx.getString("selected_text"))
        assertEquals("com.example", ctx.getString("foreground_app"))
        // Unpopulated slots must be explicit JSON null, not missing keys.
        assertTrue(ctx.has("current_input_text"))
        assertTrue(ctx.has("clipboard_text"))
        assertTrue(ctx.has("visible_url"))
        assertTrue(ctx.has("last_screenshot_path"))
        assertTrue(ctx.has("last_ui_event"))
        assertTrue(ctx.isNull("clipboard_text"))
    }

    @Test fun `envelope toJson — risk_level is lower-cased`() {
        val env = router().route("reply to this and send it")!!
        assertEquals("high", env.toJson().getString("risk_level"))
    }
}
