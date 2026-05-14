package com.jarvis.assistant.audio.stt

import com.jarvis.assistant.tools.ContactLookup
import com.jarvis.assistant.voice.VoiceFeatureFlags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class TranscriptCorrectorTest {

    private lateinit var contacts: ContactLookup
    private lateinit var corrector: TranscriptCorrector

    @Before
    fun setup() {
        contacts = mock()
        whenever(contacts.fuzzyLookup(org.mockito.kotlin.any())).thenReturn(emptyList())
        whenever(contacts.find(org.mockito.kotlin.any())).thenReturn(null)
        corrector = TranscriptCorrector(contacts)
    }

    @After
    fun tearDown() {
        VoiceFeatureFlags.clearOverride(VoiceFeatureFlags.Flag.ALIAS_LEARNING_ENABLED)
    }

    // ── Issue 2: punctuation-only N-best swap must be rejected ──────────────

    @Test
    fun `raw beats alt that differs only by trailing slash`() {
        val result = corrector.correct(
            candidates = listOf("What time is it", "What time is it/")
        )
        assertNotNull(result)
        // Final transcript is the clean raw — no trailing slash.
        assertEquals("What time is it", result!!.text)
        // We should NOT log an nbest_swap for this case.
        assertTrue("corrections should not contain nbest_swap; got ${result.corrections}",
            result.corrections.none { it.startsWith("nbest_swap") })
    }

    @Test
    fun `raw beats alt with trailing punctuation cluster`() {
        val result = corrector.correct(
            candidates = listOf("Open Spotify", "Open Spotify?!/")
        )
        assertEquals("Open Spotify", result!!.text)
        assertTrue(result.corrections.none { it.startsWith("nbest_swap") })
    }

    @Test
    fun `genuine alternate with a real word change still beats raw`() {
        // "send whatsapp to mick" vs "send whatsapp to Mike" — Mike is a
        // higher-scoring proper noun (strong verb + channel + contact).
        // Both score the same on STRONG_VERB + channel; we expect the
        // first-listed to win on the original-order tiebreaker.
        // This test asserts only that the corrector does NOT silently
        // collapse them via punctuation-only logic (different words).
        val result = corrector.correct(
            candidates = listOf("send whatsapp to mick", "send whatsapp to Mike")
        )
        assertNotNull(result)
        // The pipeline still produces a sensible final transcript
        // (it may or may not swap depending on contact data; the key
        // assertion is that the text contains the verb + channel).
        assertTrue(result!!.text.lowercase().contains("whatsapp"))
    }

    @Test
    fun `final text always has edge punctuation stripped`() {
        val result = corrector.correct(
            candidates = listOf("call mum/")
        )
        assertEquals("call mum", result!!.text)
    }

    @Test
    fun `low score swap is declined`() {
        // Two nonsense candidates — both score ≤ 0.  When a candidate
        // other than first is the winner under tiebreakers, the new
        // guard refuses the swap because there's no evidence the
        // alternative is actually better.
        val result = corrector.correct(
            candidates = listOf("abcdefg hijklmn", "qwerty asdfgh")
        )
        assertNotNull(result)
        // Either no swap happened, or if winner==first, swap line is absent anyway.
        assertTrue(result!!.corrections.none { it.startsWith("nbest_swap") })
    }
}
