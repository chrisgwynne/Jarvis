package com.jarvis.assistant.tools.device

import com.jarvis.assistant.vision.VisualContextStore
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Tests trigger matching for the three new vision tools:
 *  - [OcrScanTool] trigger patterns
 *  - [SelfieCaptureTool] trigger patterns
 *  - [VisualFollowupTool] activation guard + trigger patterns
 *
 * All regex patterns are copied from the companion objects to keep
 * tests self-contained (tools require Android Context to construct).
 */
class VisionIntentRoutingTest {

    // ── OcrScanTool patterns ─────────────────────────────────────────────────

    private val OCR_TRIGGERS = Regex(
        """read\s+this""" +
        """|what\s+does\s+this\s+say""" +
        """|what(?:'s|\s+is)\s+(?:written|on)\s+(?:it|there|this)""" +
        """|scan\s+this""" +
        """|extract\s+(?:the\s+)?text""" +
        """|read\s+(?:the\s+)?(?:label|sign|notice|poster|price|tag)""" +
        """|what\s+does\s+(?:the\s+)?(?:label|sign|notice|poster|it)\s+say""" +
        """|(?:read|scan|transcribe)\s+(?:this\s+)?document""" +
        """|what\s+does\s+it\s+say""",
        RegexOption.IGNORE_CASE
    )

    // ── SelfieCaptureTool patterns ───────────────────────────────────────────

    private val SELFIE_TRIGGERS = Regex(
        """take\s+a\s+selfie""" +
        """|selfie\s+(?:photo|picture|shot)?""" +
        """|front\s+camera\s+(?:photo|picture|shot)?""" +
        """|photo\s+of\s+(?:my|me)\s+(?:face|self)?""" +
        """|(?:picture|photo)\s+of\s+me(?:\s+please)?""",
        RegexOption.IGNORE_CASE
    )

    // ── VisualFollowupTool patterns ──────────────────────────────────────────

    private val READ_AGAIN = Regex(
        """read\s+(?:that|it)(?:\s+again)?|say\s+(?:that|it)\s+again|repeat\s+(?:that|it)""",
        RegexOption.IGNORE_CASE
    )
    private val SHOW_IT = Regex(
        """show\s+(?:me\s+)?(?:that|it)|open\s+(?:that|it|the\s+(?:image|photo|screenshot))""",
        RegexOption.IGNORE_CASE
    )
    private val SEND_IT = Regex(
        """send\s+(?:that|it|this)\s+to|share\s+(?:that|it|this)(?:\s+with)?""",
        RegexOption.IGNORE_CASE
    )
    private val SAVE_IT = Regex(
        """save\s+(?:that|it|this)""",
        RegexOption.IGNORE_CASE
    )
    private val FOLLOWUP_ALL = Regex(
        """${READ_AGAIN.pattern}|${SHOW_IT.pattern}|${SEND_IT.pattern}|${SAVE_IT.pattern}""",
        RegexOption.IGNORE_CASE
    )

    private lateinit var store: VisualContextStore

    @Before
    fun setUp() {
        store = VisualContextStore()
    }

    // ── OcrScanTool positives ─────────────────────────────────────────────────

    @Test fun `read this matches OCR`()       { assertMatches(OCR_TRIGGERS, "read this") }
    @Test fun `what does this say matches OCR`() { assertMatches(OCR_TRIGGERS, "what does this say") }
    @Test fun `scan this matches OCR`()       { assertMatches(OCR_TRIGGERS, "scan this") }
    @Test fun `extract the text matches OCR`(){ assertMatches(OCR_TRIGGERS, "extract the text") }
    @Test fun `extract text matches OCR`()   { assertMatches(OCR_TRIGGERS, "extract text") }
    @Test fun `read the label matches OCR`() { assertMatches(OCR_TRIGGERS, "read the label") }
    @Test fun `read the sign matches OCR`()  { assertMatches(OCR_TRIGGERS, "read the sign") }
    @Test fun `what does the label say matches OCR`() { assertMatches(OCR_TRIGGERS, "what does the label say") }
    @Test fun `what does it say matches OCR`() { assertMatches(OCR_TRIGGERS, "what does it say") }
    @Test fun `read this document matches OCR`() { assertMatches(OCR_TRIGGERS, "read this document") }
    @Test fun `transcribe document matches OCR`() { assertMatches(OCR_TRIGGERS, "transcribe document") }
    @Test fun `what's written there matches OCR`() { assertMatches(OCR_TRIGGERS, "what's written there") }
    @Test fun `what is on this matches OCR`() { assertMatches(OCR_TRIGGERS, "what is on this") }

    // ── OcrScanTool negatives ─────────────────────────────────────────────────

    @Test fun `look at this does NOT match OCR`() { assertNoMatch(OCR_TRIGGERS, "look at this") }
    @Test fun `take a photo does NOT match OCR`() { assertNoMatch(OCR_TRIGGERS, "take a photo") }
    @Test fun `what is the weather does NOT match OCR`() { assertNoMatch(OCR_TRIGGERS, "what is the weather") }

    // ── SelfieCaptureTool positives ───────────────────────────────────────────

    @Test fun `take a selfie matches`()         { assertMatches(SELFIE_TRIGGERS, "take a selfie") }
    @Test fun `selfie photo matches`()          { assertMatches(SELFIE_TRIGGERS, "selfie photo") }
    @Test fun `selfie picture matches`()        { assertMatches(SELFIE_TRIGGERS, "selfie picture") }
    @Test fun `front camera photo matches`()    { assertMatches(SELFIE_TRIGGERS, "front camera photo") }
    @Test fun `photo of my face matches`()      { assertMatches(SELFIE_TRIGGERS, "photo of my face") }
    @Test fun `picture of me please matches`()  { assertMatches(SELFIE_TRIGGERS, "picture of me please") }
    @Test fun `photo of me matches`()           { assertMatches(SELFIE_TRIGGERS, "photo of me") }

    // ── SelfieCaptureTool negatives ───────────────────────────────────────────

    @Test fun `show me the selfie does NOT match`()  { assertNoMatch(SELFIE_TRIGGERS, "show me the selfie") }
    @Test fun `take a rear photo does NOT match`()   { assertNoMatch(SELFIE_TRIGGERS, "take a rear photo") }
    @Test fun `send the selfie does NOT match`()     { assertNoMatch(SELFIE_TRIGGERS, "send the selfie") }

    // ── VisualFollowupTool activation guard ───────────────────────────────────

    @Test
    fun `followup does NOT match when store has no context`() {
        // store.hasContext is false (empty) — guard prevents match
        val phrases = listOf("read that again", "show me that", "send that to Mike", "save that")
        for (phrase in phrases) {
            val matches = store.hasContext && FOLLOWUP_ALL.containsMatchIn(phrase)
            assertNull("'$phrase' should not match when store empty", if (matches) phrase else null)
        }
    }

    @Test
    fun `followup matches when store has active context`() {
        store.update(VisualContextStore.VisualContext(
            source       = VisualContextStore.Source.PHONE_CAMERA,
            imageFilePath = "/data/test.jpg",
        ))
        assertTrue(store.hasContext)
        assertMatches(READ_AGAIN, "read that again")
        assertMatches(SHOW_IT, "show me that")
        assertMatches(SEND_IT, "send that to Mike")
        assertMatches(SAVE_IT, "save that")
    }

    // ── VisualFollowupTool trigger positives ──────────────────────────────────

    @Test fun `read that matches READ_AGAIN`()     { assertMatches(READ_AGAIN, "read that") }
    @Test fun `read it again matches READ_AGAIN`() { assertMatches(READ_AGAIN, "read it again") }
    @Test fun `say that again matches READ_AGAIN`(){ assertMatches(READ_AGAIN, "say that again") }
    @Test fun `repeat that matches READ_AGAIN`()   { assertMatches(READ_AGAIN, "repeat that") }
    @Test fun `show that matches SHOW_IT`()        { assertMatches(SHOW_IT, "show that") }
    @Test fun `show me that matches SHOW_IT`()     { assertMatches(SHOW_IT, "show me that") }
    @Test fun `open the image matches SHOW_IT`()   { assertMatches(SHOW_IT, "open the image") }
    @Test fun `send that to Sam matches SEND_IT`() { assertMatches(SEND_IT, "send that to Sam") }
    @Test fun `share this with matches SEND_IT`()  { assertMatches(SEND_IT, "share this with") }
    @Test fun `save that matches SAVE_IT`()        { assertMatches(SAVE_IT, "save that") }
    @Test fun `save it matches SAVE_IT`()          { assertMatches(SAVE_IT, "save it") }

    // ── VisualFollowupTool negatives ──────────────────────────────────────────

    @Test fun `read this does NOT match READ_AGAIN`()  { assertNoMatch(READ_AGAIN, "read this") }
    @Test fun `say hello does NOT match READ_AGAIN`()  { assertNoMatch(READ_AGAIN, "say hello") }
    @Test fun `open chrome does NOT match SHOW_IT`()   { assertNoMatch(SHOW_IT, "open chrome") }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun assertMatches(regex: Regex, input: String) {
        assertNotNull("Expected '$input' to match but it didn't", regex.find(input))
    }

    private fun assertNoMatch(regex: Regex, input: String) {
        assertNull("Expected '$input' NOT to match but it did", regex.find(input))
    }

    private fun assertTrue(message: String, value: Boolean) {
        org.junit.Assert.assertTrue(message, value)
    }
}
