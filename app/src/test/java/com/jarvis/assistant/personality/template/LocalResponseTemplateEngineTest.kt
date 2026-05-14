package com.jarvis.assistant.personality.template

import com.jarvis.assistant.personality.JokeFrequency
import com.jarvis.assistant.personality.PersonalitySettings
import com.jarvis.assistant.personality.SarcasmLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalResponseTemplateEngineTest {

    @Test fun `unknown category returns a sane plain fallback`() {
        val engine = LocalResponseTemplateEngine(rng = { 0.5 })
        val out = engine.choose("totally_unknown_category")
        assertTrue("expected non-blank fallback, got '$out'", out.isNotBlank())
    }

    @Test fun `serious mode forces plain pool`() {
        val engine = LocalResponseTemplateEngine(rng = { 0.95 }) // would otherwise pick witty
        repeat(20) {
            val out = engine.choose("flashlight_on",
                settings = PersonalitySettings.DEFAULT.copy(
                    sarcasm = SarcasmLevel.HIGH,
                    jokeFrequency = JokeFrequency.OFTEN,
                ),
                serious = true,
            )
            assertFalse("serious mode must not pick witty line for flashlight_on, " +
                "got '$out'",
                out.contains("cave", ignoreCase = true) ||
                    out.contains("blind", ignoreCase = true))
        }
    }

    @Test fun `sarcasm OFF forces plain pool`() {
        val engine = LocalResponseTemplateEngine(rng = { 0.99 })
        repeat(20) {
            val out = engine.choose("flashlight_on",
                settings = PersonalitySettings.DEFAULT.copy(sarcasm = SarcasmLevel.OFF),
            )
            assertFalse("sarcasm OFF must produce plain replies, got '$out'",
                out.contains("cave", ignoreCase = true) ||
                    out.contains("blind", ignoreCase = true))
        }
    }

    @Test fun `recent phrase store dedupes consecutive picks`() {
        val store = RecentPhraseStore()
        val engine = LocalResponseTemplateEngine(recent = store, rng = { 0.5 })
        val first = engine.choose("flashlight_on")
        val second = engine.choose("flashlight_on")
        // Whether it picked witty or plain doesn't matter — what matters is
        // the engine attempts to vary; second should not match first when
        // multiple options exist.
        assertNotNull(second)
        // If both plain pool entries are exhausted the engine may legitimately
        // recycle, so we only assert variety when the category has multiple options.
        assertNotEquals("with multiple options the engine should vary across calls",
            first, second)
    }

    @Test fun `witty pool is not selected when applyToLocalConfirmations is false`() {
        val engine = LocalResponseTemplateEngine(rng = { 0.99 })
        val out = engine.choose("flashlight_on",
            settings = PersonalitySettings.DEFAULT.copy(applyToLocalConfirmations = false),
        )
        assertFalse("witty pool must be skipped when applyToLocalConfirmations=false",
            out.contains("cave", ignoreCase = true) ||
                out.contains("blind", ignoreCase = true))
    }
}
