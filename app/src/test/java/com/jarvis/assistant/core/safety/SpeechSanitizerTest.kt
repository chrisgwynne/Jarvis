package com.jarvis.assistant.core.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSanitizerTest {

    private val FB = SpeechSanitizer.FRIENDLY_FALLBACK

    // ── Catches every required pattern ────────────────────────────────────

    @Test
    fun `clean text passes through unchanged`() {
        val r = SpeechSanitizer.sanitizeForSpeech("Volume lowered.")
        assertFalse(r.hadLeak)
        assertEquals("Volume lowered.", r.text)
    }

    @Test
    fun `bare HTTP 429 is sanitised`() {
        val r = SpeechSanitizer.sanitizeForSpeech("HTTP 429: too many requests")
        assertTrue(r.hadLeak)
        assertEquals(FB, r.text)
        assertEquals("http_status", r.leakKind)
    }

    @Test
    fun `stack frame is sanitised`() {
        val raw = "Failed at com.jarvis.runtime.JarvisRuntime.foo(JarvisRuntime.kt:123)"
        val r = SpeechSanitizer.sanitizeForSpeech(raw)
        assertTrue(r.hadLeak)
        assertEquals(FB, r.text)
    }

    @Test
    fun `Exception class name is sanitised`() {
        val r = SpeechSanitizer.sanitizeForSpeech("NullPointerException: foo was null")
        assertTrue(r.hadLeak)
    }

    @Test
    fun `bare Exception word is sanitised`() {
        val r = SpeechSanitizer.sanitizeForSpeech("There was an Exception while processing.")
        assertTrue(r.hadLeak)
    }

    @Test
    fun `package com_jarvis is sanitised`() {
        val r = SpeechSanitizer.sanitizeForSpeech(
            "Something at com.jarvis.assistant.foo failed"
        )
        assertTrue(r.hadLeak)
        assertEquals("package_com_jarvis", r.leakKind)
    }

    @Test
    fun `kotlin package reference is sanitised`() {
        val r = SpeechSanitizer.sanitizeForSpeech("at kotlin.Result.unwrap")
        assertTrue(r.hadLeak)
    }

    @Test
    fun `java package reference is sanitised`() {
        val r = SpeechSanitizer.sanitizeForSpeech("java.lang.NullPointerException")
        assertTrue(r.hadLeak)
    }

    @Test
    fun `JSON error body is sanitised`() {
        val r = SpeechSanitizer.sanitizeForSpeech("""{"error": "rate_limited", "code": 429}""")
        assertTrue(r.hadLeak)
    }

    @Test
    fun `Authorization header is sanitised`() {
        val r = SpeechSanitizer.sanitizeForSpeech("Authorization: Bearer abc123def456ghi789")
        assertTrue(r.hadLeak)
    }

    @Test
    fun `Bearer token is sanitised`() {
        val r = SpeechSanitizer.sanitizeForSpeech("Using Bearer abcdefghijklmnop1234")
        assertTrue(r.hadLeak)
    }

    @Test
    fun `URL with secret credentials is sanitised`() {
        val r = SpeechSanitizer.sanitizeForSpeech("Calling https://user:supersecret@example.com")
        assertTrue(r.hadLeak)
    }

    @Test
    fun `stacktrace word is sanitised`() {
        val r = SpeechSanitizer.sanitizeForSpeech("Here's the stack trace for you")
        assertTrue(r.hadLeak)
    }

    @Test
    fun `file colon line ref is sanitised`() {
        val r = SpeechSanitizer.sanitizeForSpeech("Crashed in MainActivity.kt:42")
        assertTrue(r.hadLeak)
    }

    // ── Friendly mapping outputs that resemble errors stay clean ──────────

    @Test
    fun `friendly fallback string is not itself flagged`() {
        // Important: the sanitizer must not lock itself into an infinite
        // loop by flagging its own output.
        val r = SpeechSanitizer.sanitizeForSpeech(SpeechSanitizer.FRIENDLY_FALLBACK)
        assertFalse(r.hadLeak)
    }

    @Test
    fun `That didn't work passes through`() {
        val r = SpeechSanitizer.sanitizeForSpeech("That didn't work. I've logged it.")
        assertFalse(r.hadLeak)
    }

    @Test
    fun `I am at my limit passes through`() {
        val r = SpeechSanitizer.sanitizeForSpeech("I'm at my limit for that right now.")
        assertFalse(r.hadLeak)
    }

    // ── Redaction helper ───────────────────────────────────────────────────

    @Test
    fun `redact masks Bearer tokens`() {
        val out = SpeechSanitizer.redact("Authorization: Bearer abc123def456ghi789")
        assertFalse("token leaked: $out", out.contains("abc123def456ghi789"))
        assertTrue(out.contains("***"))
    }

    @Test
    fun `redact masks api keys`() {
        val out = SpeechSanitizer.redact("api_key=supersecret123")
        assertFalse(out.contains("supersecret123"))
    }

    @Test
    fun `redact masks URL credentials`() {
        val out = SpeechSanitizer.redact("https://user:pw@host/path")
        assertFalse(out.contains("user:pw"))
        assertTrue(out.contains("***@"))
    }

    // ── leakKind labelling ────────────────────────────────────────────────

    @Test
    fun `leakKind identifies the most specific pattern`() {
        // Bearer token is more specific than api_key word.
        val r = SpeechSanitizer.sanitizeForSpeech("Authorization: Bearer abc1234567890def")
        assertEquals("auth_header", r.leakKind)
    }

    @Test
    fun `leakKind reports redacted snippet for the issue body`() {
        val r = SpeechSanitizer.sanitizeForSpeech(
            "Failed: HTTP 500 with Bearer abc1234567890def at com.jarvis.foo"
        )
        assertTrue(r.hadLeak)
        assertNotNull(r.redactedSnippet)
        assertFalse("snippet should mask the token",
            r.redactedSnippet!!.contains("abc1234567890def"))
    }
}
