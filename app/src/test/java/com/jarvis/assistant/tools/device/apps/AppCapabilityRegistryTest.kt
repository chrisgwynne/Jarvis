package com.jarvis.assistant.tools.device.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCapabilityRegistryTest {

    @Test
    fun `find resolves canonical names`() {
        assertEquals("Firefox", AppCapabilityRegistry.find("firefox")!!.displayName)
        assertEquals("Chrome",  AppCapabilityRegistry.find("chrome")!!.displayName)
        assertEquals("WhatsApp", AppCapabilityRegistry.find("whatsapp")!!.displayName)
        assertEquals("Spotify", AppCapabilityRegistry.find("spotify")!!.displayName)
        assertEquals("Etsy",    AppCapabilityRegistry.find("etsy")!!.displayName)
    }

    @Test
    fun `find resolves friendly aliases`() {
        assertEquals("Chrome",       AppCapabilityRegistry.find("browser")!!.displayName)
        assertEquals("Chrome",       AppCapabilityRegistry.find("web")!!.displayName)
        assertEquals("Google Maps",  AppCapabilityRegistry.find("maps")!!.displayName)
        assertEquals("Google Photos",AppCapabilityRegistry.find("gallery")!!.displayName)
        assertEquals("Google Photos",AppCapabilityRegistry.find("photos")!!.displayName)
        assertEquals("YouTube",      AppCapabilityRegistry.find("yt")!!.displayName)
    }

    @Test
    fun `find is case-insensitive`() {
        assertEquals("Firefox", AppCapabilityRegistry.find("FIREFOX")!!.displayName)
        assertEquals("Etsy",    AppCapabilityRegistry.find("Etsy")!!.displayName)
    }

    @Test
    fun `find returns null for unknown`() {
        assertNull(AppCapabilityRegistry.find("totallyunknownapp123"))
        assertNull(AppCapabilityRegistry.find(""))
    }

    @Test
    fun `findInTranscript pulls out multi-word names`() {
        assertEquals("Google Maps",
            AppCapabilityRegistry.findInTranscript(
                "open google maps and find a cafe"
            )!!.displayName)
        assertEquals("YouTube Music",
            AppCapabilityRegistry.findInTranscript(
                "search youtube music for radiohead"
            )!!.displayName)
    }

    @Test
    fun `findInTranscript handles trailing punctuation`() {
        assertEquals("Firefox",
            AppCapabilityRegistry.findInTranscript("open firefox.")!!.displayName)
    }

    @Test
    fun `searchUrl substitutes query and URL-encodes`() {
        val cap = AppCapabilityRegistry.find("firefox")!!
        val url = AppCapabilityRegistry.searchUrl(cap, "Bambu Lab filament")!!
        assertTrue(url.startsWith("https://www.google.com/search?q="))
        assertTrue(url.contains("Bambu") || url.contains("Bambu+Lab") ||
                   url.contains("Bambu%20Lab"))
    }

    @Test
    fun `searchUrl returns null when no template`() {
        val cap = AppCapabilityRegistry.find("whatsapp")!!
        assertNull(AppCapabilityRegistry.searchUrl(cap, "anything"))
    }

    @Test
    fun `every entry has a non-empty packageName + displayName`() {
        AppCapabilityRegistry.ENTRIES.forEach {
            assertTrue("displayName blank for ${it.names}", it.displayName.isNotBlank())
            assertTrue("packageName blank for ${it.names}", it.packageName.isNotBlank())
            assertTrue("names empty for ${it.displayName}", it.names.isNotEmpty())
        }
    }
}
