package com.jarvis.assistant.core.decisions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Lightweight test of [MemoryPolicy.topicToActionClass] — the pure mapping
 * function that drives which proactive class a user-spoken dislike mutes.
 * The stateful parts of MemoryPolicy go through Room and are exercised via
 * integration tests elsewhere; this one stays unit-local.
 */
class MemoryPolicyTopicTest {

    @Test
    fun `battery maps to BATTERY`() {
        assertEquals("BATTERY", MemoryPolicy.topicToActionClass("battery"))
        assertEquals("BATTERY", MemoryPolicy.topicToActionClass("the battery"))
    }

    @Test
    fun `reminders map to REMINDER`() {
        assertEquals("REMINDER", MemoryPolicy.topicToActionClass("reminders"))
    }

    @Test
    fun `missed call and calls map to CALL`() {
        assertEquals("CALL", MemoryPolicy.topicToActionClass("call"))
        assertEquals("CALL", MemoryPolicy.topicToActionClass("missed calls"))
    }

    @Test
    fun `notifications map to NOTIFICATION`() {
        assertEquals("NOTIFICATION", MemoryPolicy.topicToActionClass("notifications"))
    }

    @Test
    fun `meeting and calendar and agenda all map to CALENDAR`() {
        assertEquals("CALENDAR", MemoryPolicy.topicToActionClass("meetings"))
        assertEquals("CALENDAR", MemoryPolicy.topicToActionClass("my calendar"))
        assertEquals("CALENDAR", MemoryPolicy.topicToActionClass("the agenda"))
    }

    @Test
    fun `location and home map to LOCATION`() {
        assertEquals("LOCATION", MemoryPolicy.topicToActionClass("location"))
        assertEquals("LOCATION", MemoryPolicy.topicToActionClass("home"))
    }

    @Test
    fun `behaviour and suggestion map to BRAIN`() {
        assertEquals("BRAIN", MemoryPolicy.topicToActionClass("behaviour suggestions"))
        assertEquals("BRAIN", MemoryPolicy.topicToActionClass("suggestions"))
    }

    @Test
    fun `unknown topics return null`() {
        assertNull(MemoryPolicy.topicToActionClass("weather"))
        assertNull(MemoryPolicy.topicToActionClass("music"))
        assertNull(MemoryPolicy.topicToActionClass(""))
    }
}
