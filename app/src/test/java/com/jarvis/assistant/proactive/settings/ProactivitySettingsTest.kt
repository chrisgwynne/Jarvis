package com.jarvis.assistant.proactive.settings

import com.jarvis.assistant.proactive.ProactiveEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactivitySettingsTest {

    private val base = ProactivitySettings.DEFAULT.copy(quietHoursEnabled = true)

    @Test
    fun `non-wrapping quiet hours`() {
        val s = base.copy(quietStartMinute = 13 * 60, quietEndMinute = 14 * 60)
        assertTrue(s.isQuietHourMinute(13 * 60))       // start inclusive
        assertTrue(s.isQuietHourMinute(13 * 60 + 30))  // middle
        assertFalse(s.isQuietHourMinute(14 * 60))      // end exclusive
        assertFalse(s.isQuietHourMinute(12 * 60))      // before
        assertFalse(s.isQuietHourMinute(15 * 60))      // after
    }

    @Test
    fun `overnight quiet hours wrap correctly`() {
        val s = base.copy(quietStartMinute = 22 * 60, quietEndMinute = 7 * 60)
        // Inside the wrap.
        assertTrue(s.isQuietHourMinute(22 * 60))
        assertTrue(s.isQuietHourMinute(23 * 60))
        assertTrue(s.isQuietHourMinute(0))
        assertTrue(s.isQuietHourMinute(6 * 60 + 59))
        // Outside.
        assertFalse(s.isQuietHourMinute(7 * 60))
        assertFalse(s.isQuietHourMinute(12 * 60))
        assertFalse(s.isQuietHourMinute(21 * 60 + 59))
    }

    @Test
    fun `quietHoursEnabled false short-circuits`() {
        val s = base.copy(
            quietHoursEnabled = false,
            quietStartMinute = 0, quietEndMinute = 1439,   // whole day
        )
        assertFalse(s.isQuietHourMinute(12 * 60))
    }

    @Test
    fun `category map covers every event type`() {
        // If a new ProactiveEventType is added without a category mapping
        // this test will throw — and that's the intended forcing function.
        for (type in ProactiveEventType.entries) {
            // Just ensure it doesn't throw — the boolean value depends on
            // the current DEFAULT category toggles and is not asserted.
            base.isCategoryEnabled(type)
        }
    }

    @Test
    fun `urgent events list is precise`() {
        assertTrue(base.isUrgentEvent(ProactiveEventType.LOW_BATTERY))
        assertTrue(base.isUrgentEvent(ProactiveEventType.MEETING_STARTING_SOON))
        assertTrue(base.isUrgentEvent(ProactiveEventType.UPCOMING_REMINDER))
        // Anything else is not urgent.
        assertFalse(base.isUrgentEvent(ProactiveEventType.UNREAD_NOTIFICATION))
        assertFalse(base.isUrgentEvent(ProactiveEventType.DAILY_AGENDA))
        assertFalse(base.isUrgentEvent(ProactiveEventType.BEHAVIORAL_LEARNING))
    }

    @Test
    fun `DEFAULT is opt-in safe`() {
        // The default policy must keep the user surprise-free on first install.
        assertFalse(
            "DEFAULT.enabled should be false — first-time users opt in",
            ProactivitySettings.DEFAULT.enabled
        )
    }

    @Test
    fun `sensitivity multipliers ordered correctly`() {
        assertTrue(ProactivitySensitivity.LOW.thresholdMultiplier >
                   ProactivitySensitivity.MEDIUM.thresholdMultiplier)
        assertTrue(ProactivitySensitivity.MEDIUM.thresholdMultiplier >
                   ProactivitySensitivity.HIGH.thresholdMultiplier)
        assertEquals(1.0f, ProactivitySensitivity.MEDIUM.thresholdMultiplier, 0.0001f)
    }
}
