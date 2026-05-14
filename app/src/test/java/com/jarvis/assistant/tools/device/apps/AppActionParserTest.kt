package com.jarvis.assistant.tools.device.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppActionParserTest {

    // ── Open ──────────────────────────────────────────────────────────────

    @Test
    fun `open Spotify yields Open(Spotify)`() {
        val a = AppActionParser.parse("open Spotify")
        assertTrue(a is AppActionParser.AppAction.Open)
        assertEquals("Spotify", (a as AppActionParser.AppAction.Open).cap.displayName)
    }

    @Test
    fun `launch Firefox yields Open(Firefox)`() {
        val a = AppActionParser.parse("launch firefox")
        assertTrue(a is AppActionParser.AppAction.Open)
        assertEquals("Firefox", (a as AppActionParser.AppAction.Open).cap.displayName)
    }

    @Test
    fun `open the door is NOT an app action (no capability)`() {
        // No registry hit → parser declines, caller falls through
        // to OpenAppTool or normal routing.
        assertNull(AppActionParser.parse("open the door"))
    }

    // ── Search ────────────────────────────────────────────────────────────

    @Test
    fun `open Firefox and search for filament`() {
        val a = AppActionParser.parse("open Firefox and search for Bambu Lab filament")
        assertTrue(a is AppActionParser.AppAction.Search)
        val s = a as AppActionParser.AppAction.Search
        assertEquals("Firefox", s.cap.displayName)
        assertEquals("Bambu Lab filament", s.query)
    }

    @Test
    fun `search Firefox for slicer settings`() {
        val a = AppActionParser.parse("search Firefox for Bambu slicer settings")
        assertTrue(a is AppActionParser.AppAction.Search)
        val s = a as AppActionParser.AppAction.Search
        assertEquals("Firefox", s.cap.displayName)
        assertEquals("Bambu slicer settings", s.query)
    }

    @Test
    fun `search Etsy for keyrings`() {
        val a = AppActionParser.parse("search Etsy for keyrings")
        assertTrue(a is AppActionParser.AppAction.Search)
        assertEquals("Etsy", (a as AppActionParser.AppAction.Search).cap.displayName)
        assertEquals("keyrings", a.query)
    }

    @Test
    fun `search Maps for petrol stations`() {
        val a = AppActionParser.parse("search Maps for petrol stations")
        assertTrue(a is AppActionParser.AppAction.Search)
        assertEquals("Google Maps", (a as AppActionParser.AppAction.Search).cap.displayName)
        assertEquals("petrol stations", a.query)
    }

    @Test
    fun `search YouTube for Bambu tutorials`() {
        val a = AppActionParser.parse("search YouTube for Bambu tutorials")
        assertTrue(a is AppActionParser.AppAction.Search)
        assertEquals("YouTube", (a as AppActionParser.AppAction.Search).cap.displayName)
    }

    @Test
    fun `search eBay for sublimation blanks`() {
        val a = AppActionParser.parse("search eBay for sublimation blanks")
        assertTrue(a is AppActionParser.AppAction.Search)
        assertEquals("eBay", (a as AppActionParser.AppAction.Search).cap.displayName)
        assertEquals("sublimation blanks", a.query)
    }

    // ── Google verb ───────────────────────────────────────────────────────

    @Test
    fun `Google X resolves to a browser search`() {
        val a = AppActionParser.parse("Google 3D printer nozzles")
        assertTrue("expected Search or WebSearch, got $a",
            a is AppActionParser.AppAction.Search ||
            a is AppActionParser.AppAction.WebSearch)
    }

    // ── Generic web search ────────────────────────────────────────────────

    @Test
    fun `search the web for Etsy SEO tips`() {
        val a = AppActionParser.parse("search the web for Etsy SEO tips")
        assertTrue(a is AppActionParser.AppAction.WebSearch)
        assertEquals("Etsy SEO tips", (a as AppActionParser.AppAction.WebSearch).query)
    }

    @Test
    fun `look up X yields WebSearch`() {
        val a = AppActionParser.parse("look up the weather in Wrexham")
        assertTrue(a is AppActionParser.AppAction.WebSearch)
    }

    // ── Predicate ────────────────────────────────────────────────────────

    @Test
    fun `looksLikeAppCommand catches every supported form`() {
        listOf(
            "open Spotify",
            "open Firefox and search for filament",
            "search Etsy for keyrings",
            "Google something",
            "search the web for anything",
            "launch chrome",
        ).forEach {
            assertTrue("'$it' should look like an app command",
                AppActionParser.looksLikeAppCommand(it))
        }
    }

    @Test
    fun `looksLikeAppCommand declines unrelated`() {
        listOf(
            "what time is it",
            "turn the volume down",
            "remind me to take bins out",
        ).forEach {
            assertTrue("'$it' should NOT look like an app command",
                !AppActionParser.looksLikeAppCommand(it))
        }
    }

    // ── Blank ─────────────────────────────────────────────────────────────

    @Test
    fun `blank returns null`() {
        assertNull(AppActionParser.parse(""))
        assertNull(AppActionParser.parse("   "))
    }
}
