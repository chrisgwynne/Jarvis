package com.jarvis.assistant.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceExtractorTest {

    // ── WEATHER — INCLUDE_ONLY ───────────────────────────────────────────────

    @Test
    fun `extracts INCLUDE_ONLY with condition and temperature for weather`() {
        val pref = PreferenceExtractor.extract(
            "I just prefer condition and degrees for weather"
        )
        assertNotNull(pref)
        assertEquals(ResponseDomain.WEATHER, pref!!.domain)
        assertEquals(PreferenceRuleType.INCLUDE_ONLY, pref.ruleType)
        assertTrue("condition" in pref.includeFields)
        assertTrue("temperature" in pref.includeFields)
    }

    @Test
    fun `extracts INCLUDE_ONLY for 'just tell me condition and temperature'`() {
        val pref = PreferenceExtractor.extract(
            "Just tell me the condition and temperature",
            lastDomain = ResponseDomain.WEATHER,
        )
        assertNotNull(pref)
        assertEquals(PreferenceRuleType.INCLUDE_ONLY, pref!!.ruleType)
        assertTrue("condition" in pref.includeFields)
    }

    @Test
    fun `uses lastDomain when no domain keyword present`() {
        val pref = PreferenceExtractor.extract(
            "just tell me the condition and temperature",
            lastDomain = ResponseDomain.WEATHER,
        )
        assertEquals(ResponseDomain.WEATHER, pref!!.domain)
    }

    // ── WEATHER — EXCLUDE ────────────────────────────────────────────────────

    @Test
    fun `extracts EXCLUDE for "don't tell me humidity" in weather`() {
        val pref = PreferenceExtractor.extract(
            "Don't tell me humidity for weather"
        )
        assertNotNull(pref)
        assertEquals(ResponseDomain.WEATHER, pref!!.domain)
        assertEquals(PreferenceRuleType.EXCLUDE, pref.ruleType)
        assertTrue("humidity" in pref.excludeFields)
    }

    @Test
    fun `extracts EXCLUDE for "skip wind in weather"`() {
        val pref = PreferenceExtractor.extract(
            "Skip wind in weather"
        )
        assertNotNull(pref)
        assertEquals(PreferenceRuleType.EXCLUDE, pref!!.ruleType)
        assertTrue("wind" in pref.excludeFields)
    }

    // ── LENGTH ───────────────────────────────────────────────────────────────

    @Test
    fun `extracts LENGTH BRIEF for 'keep weather brief'`() {
        val pref = PreferenceExtractor.extract(
            "Keep weather brief",
            lastDomain = ResponseDomain.WEATHER,
        )
        assertNotNull(pref)
        assertEquals(PreferenceRuleType.LENGTH, pref!!.ruleType)
        assertEquals(PreferredLength.BRIEF, pref.preferredLength)
    }

    @Test
    fun `extracts LENGTH BRIEF for 'short weather reports'`() {
        val pref = PreferenceExtractor.extract(
            "I prefer short weather reports"
        )
        assertNotNull(pref)
        assertEquals(PreferenceRuleType.LENGTH, pref!!.ruleType)
        assertEquals(PreferredLength.BRIEF, pref.preferredLength)
        assertEquals(ResponseDomain.WEATHER, pref.domain)
    }

    @Test
    fun `extracts LENGTH DETAILED for 'full weather report'`() {
        val pref = PreferenceExtractor.extract(
            "I want full weather reports from now on"
        )
        assertNotNull(pref)
        assertEquals(PreferenceRuleType.LENGTH, pref!!.ruleType)
        assertEquals(PreferredLength.DETAILED, pref.preferredLength)
    }

    // ── CALENDAR domain ──────────────────────────────────────────────────────

    @Test
    fun `extracts INCLUDE_ONLY for calendar with time field`() {
        val pref = PreferenceExtractor.extract(
            "Just tell me the title and time for calendar events"
        )
        assertNotNull(pref)
        assertEquals(ResponseDomain.CALENDAR, pref!!.domain)
        assertTrue("title" in pref.includeFields || "time" in pref.includeFields)
    }

    // ── DETAIL_LEVEL fallback ────────────────────────────────────────────────

    @Test
    fun `falls back to DETAIL_LEVEL when no field keywords match`() {
        val pref = PreferenceExtractor.extract(
            "I prefer more natural sounding answers",
            lastDomain = ResponseDomain.LLM_CHAT,
        )
        assertNotNull(pref)
        assertEquals(PreferenceRuleType.DETAIL_LEVEL, pref!!.ruleType)
        assertEquals(ResponseDomain.LLM_CHAT, pref.domain)
    }

    // ── Confirmation builder ─────────────────────────────────────────────────

    @Test
    fun `buildConfirmation for INCLUDE_ONLY`() {
        val pref = ResponsePreference(
            domain = ResponseDomain.WEATHER,
            ruleType = PreferenceRuleType.INCLUDE_ONLY,
            includeFields = listOf("condition", "temperature"),
            sourceUtterance = "just condition and temperature",
        )
        val msg = PreferenceExtractor.buildConfirmation(pref)
        assertTrue(msg.contains("condition"))
        assertTrue(msg.contains("temperature"))
        assertTrue(msg.startsWith("Got it."))
    }

    @Test
    fun `buildConfirmation for LENGTH BRIEF`() {
        val pref = ResponsePreference(
            domain = ResponseDomain.CALENDAR,
            ruleType = PreferenceRuleType.LENGTH,
            preferredLength = PreferredLength.BRIEF,
            sourceUtterance = "keep it brief",
        )
        val msg = PreferenceExtractor.buildConfirmation(pref)
        assertTrue(msg.contains("brief"))
        assertTrue(msg.startsWith("Got it."))
    }

    @Test
    fun `buildConfirmation for EXCLUDE`() {
        val pref = ResponsePreference(
            domain = ResponseDomain.WEATHER,
            ruleType = PreferenceRuleType.EXCLUDE,
            excludeFields = listOf("wind", "humidity"),
            sourceUtterance = "skip wind and humidity",
        )
        val msg = PreferenceExtractor.buildConfirmation(pref)
        assertTrue(msg.contains("wind"))
        assertTrue(msg.startsWith("Got it."))
    }

    // ── WeatherComponents format ─────────────────────────────────────────────

    @Test
    fun `WeatherComponents formats INCLUDE_ONLY for condition and temperature`() {
        val components = WeatherComponents(
            condition       = "Clear skies",
            temperature     = "11°C",
            feelsLike       = "9°C",
            wind            = "12 km/h",
            highC           = "14°C",
            lowC            = "7°C",
            precipitationMm = 0.0,
        )
        val pref = ResponsePreference(
            domain = ResponseDomain.WEATHER,
            ruleType = PreferenceRuleType.INCLUDE_ONLY,
            includeFields = listOf("condition", "temperature"),
            sourceUtterance = "just condition and temperature",
        )
        val result = components.format(pref)
        assertNotNull(result)
        assertTrue(result!!.contains("Clear skies"))
        assertTrue(result.contains("11°C"))
        // Should not contain wind or humidity
        assertTrue(!result.contains("12 km/h"))
    }

    @Test
    fun `WeatherComponents defaults when pref is inactive`() {
        val components = WeatherComponents(
            condition       = "Overcast",
            temperature     = "8°C",
            feelsLike       = null,
            wind            = null,
            highC           = null,
            lowC            = null,
            precipitationMm = null,
        )
        val pref = ResponsePreference(
            domain = ResponseDomain.WEATHER,
            ruleType = PreferenceRuleType.INCLUDE_ONLY,
            includeFields = listOf("condition"),
            sourceUtterance = "just the condition",
            confidence = 0.3f, // below isActive threshold
        )
        val result = components.format(pref)
        assertNull(result) // inactive pref returns null — caller uses defaultFormat()
    }
}
