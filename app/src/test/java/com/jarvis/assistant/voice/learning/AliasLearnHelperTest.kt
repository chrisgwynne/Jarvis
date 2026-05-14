package com.jarvis.assistant.voice.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the pure parsing in [AliasLearnHelper.parse].
 *
 * The store-side recording path is exercised through [tryRecord]'s log
 * branches but the parser is the meat; testing it directly keeps these
 * tests fast and free of Android dependencies.
 */
class AliasLearnHelperTest {

    @Test fun `no I meant WhatsApp records what's-up alias in messaging context`() {
        val r = AliasLearnHelper.parse(
            previousTranscript = "send a what's up to Mike",
            correctionUtter    = "no I meant WhatsApp"
        )
        assertNotNull(r)
        assertEquals("WhatsApp",                                 r!!.intended)
        assertEquals(AliasLearningStore.Context_.MESSAGING,      r.ctx)
        // The closest mishear is "what's" (Levenshtein vs "WhatsApp").
        assertEquals("what's",                                   r.heard)
    }

    @Test fun `no I said Cath records cat alias in contact context`() {
        val r = AliasLearnHelper.parse(
            previousTranscript = "call cat please",
            correctionUtter    = "no I said Cath"
        )
        assertNotNull(r)
        assertEquals("Cath",                                     r!!.intended)
        assertEquals("cat",                                      r.heard)
        // "call <name>" is a contact context.
        assertEquals(AliasLearningStore.Context_.CONTACT,        r.ctx)
    }

    @Test fun `not cat Cath form also parses`() {
        val r = AliasLearnHelper.parse(
            previousTranscript = "message cat hello",
            correctionUtter    = "not cat, Cath"
        )
        assertNotNull(r)
        assertEquals("Cath",                                     r!!.intended)
        assertEquals(AliasLearningStore.Context_.MESSAGING,      r.ctx)
    }

    @Test fun `correction with no recent transcript returns null`() {
        val r = AliasLearnHelper.parse(
            previousTranscript = "",
            correctionUtter    = "no I meant WhatsApp"
        )
        assertNull(r)
    }

    @Test fun `correction with no recognisable phrasing returns null`() {
        val r = AliasLearnHelper.parse(
            previousTranscript = "call cat please",
            correctionUtter    = "actually that's wrong"
        )
        assertNull(r)
    }

    @Test fun `correction where intended word is identical to previous token does not match itself`() {
        // "no I meant Cath" but previous already contained Cath verbatim →
        // no mishear to learn from.
        val r = AliasLearnHelper.parse(
            previousTranscript = "message Cath hello",
            correctionUtter    = "no I meant Cath"
        )
        assertNull(r)
    }

    @Test fun `device-context correction is recorded as DEVICE`() {
        val r = AliasLearnHelper.parse(
            previousTranscript = "turn on the light song",
            correctionUtter    = "no I meant lights"
        )
        assertNotNull(r)
        assertEquals(AliasLearningStore.Context_.DEVICE,         r!!.ctx)
        assertEquals("lights",                                   r.intended)
    }

    @Test fun `app-context correction is recorded as APP`() {
        val r = AliasLearnHelper.parse(
            previousTranscript = "open spot of tea",
            correctionUtter    = "no I meant Spotify"
        )
        assertNotNull(r)
        assertEquals(AliasLearningStore.Context_.APP,            r!!.ctx)
        assertEquals("Spotify",                                  r.intended)
    }
}
