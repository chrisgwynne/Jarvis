package com.jarvis.assistant.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceDetectorTest {

    // ── Positive preference detection ────────────────────────────────────────

    @Test
    fun `detects 'I just prefer' phrase`() {
        assertTrue(PreferenceDetector.isPreference("I just prefer condition and degrees"))
    }

    @Test
    fun `detects 'I prefer' phrase`() {
        assertTrue(PreferenceDetector.isPreference("I prefer shorter weather reports"))
    }

    @Test
    fun `detects 'next time' phrase`() {
        assertTrue(PreferenceDetector.isPreference("Next time, just give me the temperature"))
    }

    @Test
    fun `detects 'from now on' phrase`() {
        assertTrue(PreferenceDetector.isPreference("From now on keep weather brief"))
    }

    @Test
    fun `detects 'just tell me' phrase`() {
        assertTrue(PreferenceDetector.isPreference("just tell me the condition and temperature"))
    }

    @Test
    fun `detects "don't tell me" phrase`() {
        assertTrue(PreferenceDetector.isPreference("Don't tell me the wind speed"))
    }

    @Test
    fun `detects 'skip' phrase`() {
        assertTrue(PreferenceDetector.isPreference("Skip the humidity in weather"))
    }

    @Test
    fun `detects 'keep it brief' phrase`() {
        assertTrue(PreferenceDetector.isPreference("Keep it brief for weather"))
    }

    @Test
    fun `detects 'whenever' phrase`() {
        assertTrue(PreferenceDetector.isPreference("Whenever you tell me the weather, just condition and degrees"))
    }

    @Test
    fun `detects 'i only want' phrase`() {
        assertTrue(PreferenceDetector.isPreference("I only want the temperature for weather"))
    }

    // ── Override detection ───────────────────────────────────────────────────

    @Test
    fun `detects 'give me the full' override`() {
        assertTrue(PreferenceDetector.isOverride("Give me the full weather report"))
    }

    @Test
    fun `detects 'this time' override`() {
        assertTrue(PreferenceDetector.isOverride("This time, give me everything"))
    }

    @Test
    fun `detects 'just this once' override`() {
        assertTrue(PreferenceDetector.isOverride("Just this once, full details please"))
    }

    @Test
    fun `detects 'full report' override`() {
        assertTrue(PreferenceDetector.isOverride("Can you give me the full report"))
    }

    // ── Override does not also fire as preference ────────────────────────────

    @Test
    fun `override phrase is not detected as preference`() {
        assertFalse(PreferenceDetector.isPreference("Give me the full weather report this time"))
    }

    @Test
    fun `'this time give me full details' is override not preference`() {
        assertFalse(PreferenceDetector.isPreference("This time give me full details"))
    }

    // ── Negative cases ───────────────────────────────────────────────────────

    @Test
    fun `ordinary question is not preference`() {
        assertFalse(PreferenceDetector.isPreference("What's the weather like today?"))
    }

    @Test
    fun `ordinary question is not override`() {
        assertFalse(PreferenceDetector.isOverride("What's the weather like today?"))
    }

    @Test
    fun `reminder request is not preference`() {
        assertFalse(PreferenceDetector.isPreference("Remind me at 3pm about football"))
    }

    @Test
    fun `calendar query is not preference`() {
        assertFalse(PreferenceDetector.isPreference("What's on my calendar today?"))
    }
}
