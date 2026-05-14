package com.jarvis.assistant.personality

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PersonalityPromptSelectorTest — verifies the per-interaction
 * section mapping, serious-mode filtering, and humour suppression
 * when sarcasm is OFF.  Pure JVM — no Android assets needed (we
 * inject a stub [PersonalityContext]).
 */
class PersonalityPromptSelectorTest {

    private val ctx = PersonalityContext(
        mapOf(
            PersonalitySection.IDENTITY                   to "ID body",
            PersonalitySection.SOUL                       to "SOUL body",
            PersonalitySection.SPEECH_STYLE               to "SPEECH body",
            PersonalitySection.HUMOUR                     to "HUMOUR body",
            PersonalitySection.PUSHBACK                   to "PUSHBACK body",
            PersonalitySection.PROACTIVITY_STYLE          to "PROACTIVITY body",
            PersonalitySection.ERROR_STYLE                to "ERROR body",
            PersonalitySection.COMMAND_CONFIRMATION_STYLE to "CONFIRM body",
            PersonalitySection.MEMORY_RULES               to "MEMORY body",
            PersonalitySection.BOUNDARIES                 to "BOUNDARIES body",
        ),
    )

    @Test fun `LLM_CHAT includes identity, soul, humour, pushback, boundaries`() {
        val sel = PersonalityPromptSelector(ctx)
        val out = sel.promptFor(InteractionType.LLM_CHAT)
        listOf("ID body", "SOUL body", "SPEECH body", "HUMOUR body",
            "PUSHBACK body", "BOUNDARIES body").forEach {
            assertTrue("LLM_CHAT prompt should include '$it'", it in out)
        }
        assertFalse("LLM_CHAT must not pull in error style", "ERROR body" in out)
        assertFalse("LLM_CHAT must not pull in memory rules", "MEMORY body" in out)
    }

    @Test fun `ERROR_MESSAGE only uses error style + boundaries`() {
        val sel = PersonalityPromptSelector(ctx)
        val out = sel.promptFor(InteractionType.ERROR_MESSAGE)
        assertTrue("ERROR body" in out)
        assertTrue("BOUNDARIES body" in out)
        assertFalse("HUMOUR body" in out)
        assertFalse("PUSHBACK body" in out)
    }

    @Test fun `serious mode strips humour`() {
        val sel = PersonalityPromptSelector(ctx)
        val out = sel.promptFor(InteractionType.LLM_CHAT, serious = true)
        assertFalse("humour must be suppressed in serious mode", "HUMOUR body" in out)
        assertTrue("identity must remain in serious mode", "ID body" in out)
        assertTrue("Serious mode is ON" in out)
    }

    @Test fun `sarcasm OFF strips humour even when not serious`() {
        val sel = PersonalityPromptSelector(
            ctx,
            settings = { PersonalitySettings.DEFAULT.copy(sarcasm = SarcasmLevel.OFF) },
        )
        val out = sel.promptFor(InteractionType.LLM_CHAT)
        assertFalse("HUMOUR body" in out)
    }

    @Test fun `disabled personality returns empty string`() {
        val sel = PersonalityPromptSelector(
            ctx,
            settings = { PersonalitySettings.DEFAULT.copy(enabled = false) },
        )
        assertTrue(sel.promptFor(InteractionType.LLM_CHAT).isEmpty())
    }

    @Test fun `PROACTIVE_REMINDER uses proactivity style + boundaries`() {
        val sel = PersonalityPromptSelector(ctx)
        val out = sel.promptFor(InteractionType.PROACTIVE_REMINDER)
        assertTrue("PROACTIVITY body" in out)
        assertTrue("BOUNDARIES body" in out)
    }
}
