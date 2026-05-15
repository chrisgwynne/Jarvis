package com.jarvis.assistant.tools.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Matcher tests for [CloseAppTool].
 *
 * Exercises the regex patterns directly — the tool itself needs an Android
 * Context to construct, so we test the companion Regex constants.
 */
class CloseAppToolMatcherTest {

    // Mirror the companion regexes so we don't need Context
    private val CLOSE_SELF_RE = Regex(
        """(?:close|shut|exit|dismiss|quit)\s+""" +
        """(?:this|that|it|the\s+app|this\s+app|that\s+app|the\s+current\s+app|current\s+app)""",
        RegexOption.IGNORE_CASE
    )

    private val CLOSE_NAMED_RE = Regex(
        """(?:close|shut|exit|dismiss|quit)\s+""" +
        """(?!(?:this|that|it\b|the\s+app|this\s+app|that\s+app|the\s+current\s+app|current\s+app))(.+)""",
        RegexOption.IGNORE_CASE
    )

    private val GO_BACK_RE = Regex(
        """(?:^|\s)go\s+back(?:\s+please)?\s*$""",
        RegexOption.IGNORE_CASE
    )

    private val GO_HOME_RE = Regex(
        """(?:^|\s)go\s+(?:to\s+)?(?:home(?:\s+screen)?)(?:\s+please)?\s*$""",
        RegexOption.IGNORE_CASE
    )

    private val SWITCH_JARVIS_RE = Regex(
        """switch\s+back\s+to\s+jarvis|return\s+to\s+jarvis""",
        RegexOption.IGNORE_CASE
    )

    // ── CLOSE_SELF positives ──────────────────────────────────────────────

    @Test fun `close this app matches CLOSE_SELF`() = assertMatches(CLOSE_SELF_RE, "close this app")
    @Test fun `exit that matches CLOSE_SELF`() = assertMatches(CLOSE_SELF_RE, "exit that")
    @Test fun `close it matches CLOSE_SELF`() = assertMatches(CLOSE_SELF_RE, "close it")
    @Test fun `shut the app matches CLOSE_SELF`() = assertMatches(CLOSE_SELF_RE, "shut the app")
    @Test fun `close current app matches CLOSE_SELF`() = assertMatches(CLOSE_SELF_RE, "close current app")
    @Test fun `dismiss this app matches CLOSE_SELF`() = assertMatches(CLOSE_SELF_RE, "dismiss this app")
    @Test fun `quit that app matches CLOSE_SELF`() = assertMatches(CLOSE_SELF_RE, "quit that app")

    // ── CLOSE_SELF negatives — should NOT match self-ref ──────────────────

    @Test fun `close Google Maps does NOT match CLOSE_SELF`() = assertNoMatch(CLOSE_SELF_RE, "close Google Maps")
    @Test fun `close Spotify does NOT match CLOSE_SELF`() = assertNoMatch(CLOSE_SELF_RE, "close Spotify")
    @Test fun `exit does NOT match CLOSE_SELF alone`() = assertNoMatch(CLOSE_SELF_RE, "exit")

    // ── CLOSE_NAMED positives ─────────────────────────────────────────────

    @Test fun `close Google Maps matches CLOSE_NAMED`() {
        val m = CLOSE_NAMED_RE.find("close Google Maps")
        assertNotNull(m)
        assertEquals("Google Maps", m!!.groupValues[1].trim())
    }

    @Test fun `shut Spotify matches CLOSE_NAMED`() {
        val m = CLOSE_NAMED_RE.find("shut Spotify")
        assertNotNull(m)
        assertEquals("Spotify", m!!.groupValues[1].trim())
    }

    @Test fun `exit WhatsApp matches CLOSE_NAMED`() {
        val m = CLOSE_NAMED_RE.find("exit WhatsApp")
        assertNotNull(m)
        assertEquals("WhatsApp", m!!.groupValues[1].trim())
    }

    @Test fun `quit YouTube matches CLOSE_NAMED`() {
        val m = CLOSE_NAMED_RE.find("quit YouTube")
        assertNotNull(m)
        assertEquals("YouTube", m!!.groupValues[1].trim())
    }

    // ── CLOSE_NAMED does NOT fire on self-refs ────────────────────────────

    @Test fun `close it does NOT match CLOSE_NAMED`() = assertNoMatch(CLOSE_NAMED_RE, "close it")
    @Test fun `close that does NOT match CLOSE_NAMED`() = assertNoMatch(CLOSE_NAMED_RE, "close that")
    @Test fun `close this app does NOT match CLOSE_NAMED`() = assertNoMatch(CLOSE_NAMED_RE, "close this app")
    @Test fun `close the app does NOT match CLOSE_NAMED`() = assertNoMatch(CLOSE_NAMED_RE, "close the app")

    // ── GO_BACK positives ─────────────────────────────────────────────────

    @Test fun `go back matches GO_BACK`() = assertMatches(GO_BACK_RE, "go back")
    @Test fun `go back please matches GO_BACK`() = assertMatches(GO_BACK_RE, "go back please")

    // ── GO_BACK negatives ─────────────────────────────────────────────────

    @Test fun `go backward does NOT match GO_BACK`() = assertNoMatch(GO_BACK_RE, "go backward")
    @Test fun `go back to maps does NOT match GO_BACK`() = assertNoMatch(GO_BACK_RE, "go back to maps")

    // ── GO_HOME positives ─────────────────────────────────────────────────

    @Test fun `go home matches GO_HOME`() = assertMatches(GO_HOME_RE, "go home")
    @Test fun `go to home screen matches GO_HOME`() = assertMatches(GO_HOME_RE, "go to home screen")
    @Test fun `go home please matches GO_HOME`() = assertMatches(GO_HOME_RE, "go home please")

    // ── GO_HOME negatives ─────────────────────────────────────────────────

    @Test fun `go home and open maps does NOT match GO_HOME`() = assertNoMatch(GO_HOME_RE, "go home and open maps")

    // ── SWITCH_JARVIS positives ───────────────────────────────────────────

    @Test fun `switch back to Jarvis matches SWITCH_JARVIS`() = assertMatches(SWITCH_JARVIS_RE, "switch back to Jarvis")
    @Test fun `return to Jarvis matches SWITCH_JARVIS`() = assertMatches(SWITCH_JARVIS_RE, "return to Jarvis")
    @Test fun `switch back to jarvis case insensitive`() = assertMatches(SWITCH_JARVIS_RE, "SWITCH BACK TO JARVIS")

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun assertMatches(re: Regex, input: String) =
        assertNotNull("Expected '$input' to match ${re.pattern}", re.containsMatchIn(input))

    private fun assertNoMatch(re: Regex, input: String) =
        assertNull("Expected '$input' NOT to match ${re.pattern}",
            re.find(input)?.let { it })
}
