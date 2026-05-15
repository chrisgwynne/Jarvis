package com.jarvis.assistant.tools.device

import com.jarvis.assistant.maps.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Matcher and mode-parsing tests for [MapsNavigationFollowupTool].
 *
 * Tests the companion Regex constants + [MapsNavigationFollowupTool.parseMode]
 * without needing an Android Context.
 */
class MapsNavigationFollowupToolMatcherTest {

    // Mirror companion constants
    private val STRONG_START = Regex(
        """(?:start|begin|launch)\s+(?:driving|walking|cycling|transit|navigation|route|directions?)""" +
        """|(?:driving|walking|cycling|transit)\s+(?:start|begin)\s+directions?""" +
        """|(?:drive|walk|cycle)\s+there""" +
        """|begin\s+navigation""",
        RegexOption.IGNORE_CASE
    )

    private val MODE_SWITCH = Regex(
        """(?:switch|change|make\s+it|set\s+it\s+to)\s+to\s+(?:driving|walking|cycling|transit)""" +
        """|(?:switch|change)\s+(?:to\s+)?(?:driving|walking|cycling|transit)\s+(?:mode|directions?)?""",
        RegexOption.IGNORE_CASE
    )

    private val STOP_NAV = Regex(
        """stop\s+(?:the\s+)?(?:navigation|route|directions?)""" +
        """|end\s+(?:the\s+)?(?:route|navigation|directions?)""" +
        """|cancel\s+(?:the\s+)?(?:route|navigation|directions?)""",
        RegexOption.IGNORE_CASE
    )

    private val WEAK_START = Regex(
        """(?:^|\s)(?:go|let'?s\s+go|go\s+now)(?:\s*$|\s*please)""" +
        """|(?:^|\s)start\s+it(?:\s*$|\s*please)""" +
        """|take\s+me\s+there""" +
        """|start\s+the\s+route""",
        RegexOption.IGNORE_CASE
    )

    private val CLOSE_MAPS_NAV = Regex(
        """(?:close|exit|stop|quit)\s+(?:google\s+)?maps?""",
        RegexOption.IGNORE_CASE
    )

    // ── STRONG_START positives ────────────────────────────────────────────

    @Test fun `start driving directions matches STRONG_START`() = assertMatches(STRONG_START, "start driving directions")
    @Test fun `start walking matches STRONG_START`() = assertMatches(STRONG_START, "start walking")
    @Test fun `begin navigation matches STRONG_START`() = assertMatches(STRONG_START, "begin navigation")
    @Test fun `drive there matches STRONG_START`() = assertMatches(STRONG_START, "drive there")
    @Test fun `walk there matches STRONG_START`() = assertMatches(STRONG_START, "walk there")
    @Test fun `start the route matches STRONG_START`() = assertMatches(STRONG_START, "start the route")
    @Test fun `launch navigation matches STRONG_START`() = assertMatches(STRONG_START, "launch navigation")
    @Test fun `driving start directions matches STRONG_START`() = assertMatches(STRONG_START, "driving start directions")

    // ── STRONG_START negatives ────────────────────────────────────────────

    @Test fun `go does NOT match STRONG_START`() = assertNoMatch(STRONG_START, "go")
    @Test fun `start it does NOT match STRONG_START`() = assertNoMatch(STRONG_START, "start it")

    // ── MODE_SWITCH positives ─────────────────────────────────────────────

    @Test fun `switch to walking matches MODE_SWITCH`() = assertMatches(MODE_SWITCH, "switch to walking")
    @Test fun `change to driving matches MODE_SWITCH`() = assertMatches(MODE_SWITCH, "change to driving")
    @Test fun `make it walking matches MODE_SWITCH`() = assertMatches(MODE_SWITCH, "make it walking")
    @Test fun `switch walking mode matches MODE_SWITCH`() = assertMatches(MODE_SWITCH, "switch walking mode")

    // ── STOP_NAV positives ────────────────────────────────────────────────

    @Test fun `stop navigation matches STOP_NAV`() = assertMatches(STOP_NAV, "stop navigation")
    @Test fun `end the route matches STOP_NAV`() = assertMatches(STOP_NAV, "end the route")
    @Test fun `cancel directions matches STOP_NAV`() = assertMatches(STOP_NAV, "cancel directions")
    @Test fun `cancel the navigation matches STOP_NAV`() = assertMatches(STOP_NAV, "cancel the navigation")

    // ── STOP_NAV negatives ────────────────────────────────────────────────

    @Test fun `stop does NOT match STOP_NAV`() = assertNoMatch(STOP_NAV, "stop")
    @Test fun `end it does NOT match STOP_NAV`() = assertNoMatch(STOP_NAV, "end it")

    // ── WEAK_START positives ──────────────────────────────────────────────

    @Test fun `go matches WEAK_START`() = assertMatches(WEAK_START, "go")
    @Test fun `go please matches WEAK_START`() = assertMatches(WEAK_START, "go please")
    @Test fun `start it matches WEAK_START`() = assertMatches(WEAK_START, "start it")
    @Test fun `take me there matches WEAK_START`() = assertMatches(WEAK_START, "take me there")
    @Test fun `let's go matches WEAK_START`() = assertMatches(WEAK_START, "let's go")
    @Test fun `go now matches WEAK_START`() = assertMatches(WEAK_START, "go now")

    // ── WEAK_START negatives — must not swallow real commands ─────────────

    @Test fun `go home does NOT match WEAK_START`() = assertNoMatch(WEAK_START, "go home")
    @Test fun `go back does NOT match WEAK_START`() = assertNoMatch(WEAK_START, "go back")
    @Test fun `go ahead does NOT match WEAK_START`() = assertNoMatch(WEAK_START, "go ahead")

    // ── CLOSE_MAPS_NAV positives ──────────────────────────────────────────

    @Test fun `close Maps matches CLOSE_MAPS_NAV`() = assertMatches(CLOSE_MAPS_NAV, "close Maps")
    @Test fun `exit Google Maps matches CLOSE_MAPS_NAV`() = assertMatches(CLOSE_MAPS_NAV, "exit Google Maps")
    @Test fun `stop maps matches CLOSE_MAPS_NAV`() = assertMatches(CLOSE_MAPS_NAV, "stop maps")

    // ── parseMode ─────────────────────────────────────────────────────────

    @Test fun `parseMode driving returns DRIVING`() =
        assertEquals(TravelMode.DRIVING, MapsNavigationFollowupTool.parseMode("start driving directions"))

    @Test fun `parseMode walking returns WALKING`() =
        assertEquals(TravelMode.WALKING, MapsNavigationFollowupTool.parseMode("start walking directions"))

    @Test fun `parseMode walk there returns WALKING`() =
        assertEquals(TravelMode.WALKING, MapsNavigationFollowupTool.parseMode("walk there"))

    @Test fun `parseMode foot returns WALKING`() =
        assertEquals(TravelMode.WALKING, MapsNavigationFollowupTool.parseMode("go on foot"))

    @Test fun `parseMode cycling returns BICYCLING`() =
        assertEquals(TravelMode.BICYCLING, MapsNavigationFollowupTool.parseMode("start cycling directions"))

    @Test fun `parseMode bike returns BICYCLING`() =
        assertEquals(TravelMode.BICYCLING, MapsNavigationFollowupTool.parseMode("switch to bike"))

    @Test fun `parseMode transit returns TRANSIT`() =
        assertEquals(TravelMode.TRANSIT, MapsNavigationFollowupTool.parseMode("start transit directions"))

    @Test fun `parseMode bus returns TRANSIT`() =
        assertEquals(TravelMode.TRANSIT, MapsNavigationFollowupTool.parseMode("switch to bus"))

    @Test fun `parseMode default is DRIVING`() =
        assertEquals(TravelMode.DRIVING, MapsNavigationFollowupTool.parseMode("go"))

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun assertMatches(re: Regex, input: String) =
        assertNotNull("Expected '$input' to match ${re.pattern}", re.containsMatchIn(input))

    private fun assertNoMatch(re: Regex, input: String) =
        assertNull("Expected '$input' NOT to match ${re.pattern}",
            if (re.containsMatchIn(input)) true else null)
}
