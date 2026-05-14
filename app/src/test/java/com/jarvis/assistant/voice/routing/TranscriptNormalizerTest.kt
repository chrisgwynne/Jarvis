package com.jarvis.assistant.voice.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptNormalizerTest {

    @Test
    fun `trailing slash is stripped`() {
        assertEquals("What time is it",
            TranscriptNormalizer.normalize("What time is it/"))
    }

    @Test
    fun `trailing punctuation cluster is stripped`() {
        assertEquals("Open Spotify",
            TranscriptNormalizer.normalize("Open Spotify?!/"))
    }

    @Test
    fun `leading punctuation is stripped`() {
        assertEquals("call mum",
            TranscriptNormalizer.normalize(",,, call mum"))
    }

    @Test
    fun `whitespace runs are collapsed`() {
        assertEquals("call John Smith",
            TranscriptNormalizer.normalize("  call   John   Smith  "))
    }

    @Test
    fun `internal apostrophes and hyphens are preserved`() {
        assertEquals("what's the time",
            TranscriptNormalizer.normalize("what's the time"))
        assertEquals("video-call Mike",
            TranscriptNormalizer.normalize("video-call Mike"))
    }

    @Test
    fun `lowercase flag works`() {
        assertEquals("what time is it",
            TranscriptNormalizer.normalize("What Time Is It/", lowercase = true))
    }

    @Test
    fun `phrase rewrite - whats becomes what is in matching mode`() {
        assertEquals("what is the time",
            TranscriptNormalizer.normalizeForMatching("Whats the time?"))
        assertEquals("what is my battery",
            TranscriptNormalizer.normalizeForMatching("Whats my battery"))
    }

    @Test
    fun `phrase rewrite - switch on becomes turn on`() {
        // "switch on" → "turn on" is a strict substitution; "the" after
        // is not touched because the "turn the" rule only fires when
        // "the" follows "turn" directly.  Result is a valid Home
        // Assistant phrase (HA grammar accepts the article).
        assertEquals("turn on the kitchen light",
            TranscriptNormalizer.normalizeForMatching("switch on the kitchen light"))
        assertEquals("turn off the bedroom lamp",
            TranscriptNormalizer.normalizeForMatching("Switch off the bedroom lamp"))
    }

    @Test
    fun `phrase rewrite - send a whatsapp becomes send whatsapp`() {
        assertEquals("send whatsapp to mike saying hello",
            TranscriptNormalizer.normalizeForMatching("Send a WhatsApp to Mike saying hello"))
        assertEquals("send sms to mum",
            TranscriptNormalizer.normalizeForMatching("send an sms to mum"))
    }

    @Test
    fun `phrase rewrite - turn the X becomes turn X`() {
        // The substitution is purely "turn the" → "turn"; word order is
        // not rearranged.  "turn the kitchen light on" → "turn kitchen
        // light on" — still a valid HA-grammar phrase.
        assertEquals("turn kitchen light on",
            TranscriptNormalizer.normalizeForMatching("turn the kitchen light on"))
        // "turn off the X" is NOT rewritten — only "turn the" matches.
        // HA parsers accept both forms.
        assertEquals("turn off the kitchen lights",
            TranscriptNormalizer.normalizeForMatching("turn off the kitchen lights"))
    }

    @Test
    fun `non-matching mode preserves case and contractions`() {
        // Default mode (lowercase=false) does NOT apply phrase rewrites —
        // display / logging paths see the user's original phrasing.
        assertEquals("What's the time",
            TranscriptNormalizer.normalize("What's the time?"))
    }

    @Test
    fun `empty and blank inputs return unchanged`() {
        assertEquals("", TranscriptNormalizer.normalize(""))
        assertEquals("", TranscriptNormalizer.normalize("   "))
    }

    @Test
    fun `differsOnlyByPunctuation detects punctuation-only swaps`() {
        assertTrue(
            TranscriptNormalizer.differsOnlyByPunctuation(
                "What time is it", "What time is it/"
            )
        )
        assertTrue(
            TranscriptNormalizer.differsOnlyByPunctuation(
                "Open Spotify.", "Open Spotify"
            )
        )
        // Case differences alone also count as "only punctuation/case"
        assertTrue(
            TranscriptNormalizer.differsOnlyByPunctuation(
                "what time IS it", "What Time Is It/"
            )
        )
    }

    @Test
    fun `differsOnlyByPunctuation rejects real word changes`() {
        assertFalse(
            TranscriptNormalizer.differsOnlyByPunctuation(
                "send Mike a WhatsApp", "send Mick a WhatsApp"
            )
        )
        assertFalse(
            TranscriptNormalizer.differsOnlyByPunctuation(
                "what time is it", "what's the time"
            )
        )
    }
}
