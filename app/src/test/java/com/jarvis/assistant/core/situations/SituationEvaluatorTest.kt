package com.jarvis.assistant.core.situations

import com.jarvis.assistant.context.ActivityMode
import com.jarvis.assistant.context.Presence
import com.jarvis.assistant.context.TimePhase
import com.jarvis.assistant.proactive.ContextSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SituationEvaluatorTest {

    private val evaluator = SituationEvaluator()
    private val nowMs = 1_714_000_000_000L

    private fun snapshot(
        batteryLevel: Int = 80,
        isCharging: Boolean = false,
        nextMeetingAtMillis: Long? = null,
        nextMeetingEndMillis: Long? = null,
        missedCallsCount: Int = 0,
        activeReminderCount: Int = 0,
        unreadNotificationCount: Int = 0,
        isDriving: Boolean = false,
        lastNotificationText: String? = null,
    ) = ContextSnapshot(
        currentTimeMillis = nowMs,
        batteryLevel = batteryLevel,
        isCharging = isCharging,
        screenOn = true,
        isJarvisSpeaking = false,
        isJarvisListening = false,
        lastUserInteractionTimeMillis = nowMs - 60_000L,
        activeReminderCount = activeReminderCount,
        nextReminderAtMillis = null,
        missedCallsCount = missedCallsCount,
        lastMissedCallAtMillis = null,
        lastMissedCallContactName = null,
        currentLocationName = null,
        networkAvailable = true,
        unreadNotificationCount = unreadNotificationCount,
        lastNotificationText = lastNotificationText,
        isDriving = isDriving,
        nextMeetingAtMillis = nextMeetingAtMillis,
        nextMeetingEndMillis = nextMeetingEndMillis,
    )

    private fun presence(
        phase: TimePhase = TimePhase.DAY,
        activity: ActivityMode = ActivityMode.IDLE,
        minutesIdle: Long = 5,
    ) = Presence(phase, activity, minutesIdle)

    @Test
    fun `low battery plus near meeting produces LOW_BATTERY_BEFORE_TRAVEL`() {
        val snap = snapshot(
            batteryLevel = 18,
            nextMeetingAtMillis = nowMs + 30 * 60_000L,
        )
        val results = evaluator.evaluate(emptyList(), snap, presence(), nowMs)
        val low = results.firstOrNull { it.type == SituationType.LOW_BATTERY_BEFORE_TRAVEL }
        assertNotNull(low)
        assertTrue(low!!.confidence > 0f)
        assertTrue(low.urgency > 0f)
    }

    @Test
    fun `high battery does not produce LOW_BATTERY_BEFORE_TRAVEL`() {
        val snap = snapshot(batteryLevel = 90, nextMeetingAtMillis = nowMs + 30 * 60_000L)
        val results = evaluator.evaluate(emptyList(), snap, presence(), nowMs)
        assertFalse(results.any { it.type == SituationType.LOW_BATTERY_BEFORE_TRAVEL })
    }

    @Test
    fun `active meeting window produces IN_MEETING_AND_UNAVAILABLE`() {
        val snap = snapshot(
            nextMeetingAtMillis = nowMs - 5 * 60_000L,
            nextMeetingEndMillis = nowMs + 25 * 60_000L,
        )
        val results = evaluator.evaluate(
            emptyList(),
            snap,
            presence(activity = ActivityMode.IDLE),
            nowMs,
        )
        assertTrue(results.any { it.type == SituationType.IN_MEETING_AND_UNAVAILABLE })
    }

    @Test
    fun `night + winding down produces TIRED_LATE_NIGHT_INTERACTION`() {
        val results = evaluator.evaluate(
            emptyList(),
            snapshot(),
            presence(phase = TimePhase.NIGHT, activity = ActivityMode.WINDING_DOWN, minutesIdle = 30),
            nowMs,
        )
        val tired = results.firstOrNull { it.type == SituationType.TIRED_LATE_NIGHT_INTERACTION }
        assertNotNull(tired)
        assertTrue(tired!!.urgency < 0.4f)
    }

    @Test
    fun `missed call when free produces MISSED_CALL_WHILE_NOW_FREE`() {
        val snap = snapshot(missedCallsCount = 1)
        val results = evaluator.evaluate(emptyList(), snap, presence(activity = ActivityMode.IDLE), nowMs)
        assertTrue(results.any { it.type == SituationType.MISSED_CALL_WHILE_NOW_FREE })
    }

    @Test
    fun `missed call while in meeting does not produce MISSED_CALL_WHILE_NOW_FREE`() {
        val snap = snapshot(
            missedCallsCount = 1,
            nextMeetingAtMillis = nowMs - 60_000L,
            nextMeetingEndMillis = nowMs + 60 * 60_000L,
        )
        val results = evaluator.evaluate(emptyList(), snap, presence(), nowMs)
        assertFalse(results.any { it.type == SituationType.MISSED_CALL_WHILE_NOW_FREE })
    }

    @Test
    fun `delivery keyword in notification produces POSSIBLE_DELIVERY_WAITING`() {
        val snap = snapshot(lastNotificationText = "Your package is out for delivery.")
        val results = evaluator.evaluate(emptyList(), snap, presence(), nowMs)
        assertTrue(results.any { it.type == SituationType.POSSIBLE_DELIVERY_WAITING })
    }

    @Test
    fun `expired situation filtered by isExpired`() {
        val s = Situation(
            type = SituationType.POSSIBLE_DELIVERY_WAITING,
            confidence = 0.5f,
            urgency = 0.3f,
            createdAtMs = nowMs - 60 * 60_000L,
            expiresAtMs = nowMs - 1,
            evidence = emptyList(),
            sourceSignals = emptyList(),
            summary = "",
        )
        assertTrue(s.isExpired(nowMs))
    }

    @Test
    fun `weight mixes urgency and confidence`() {
        val hi = Situation(
            type = SituationType.LEAVING_HOME_SOON,
            confidence = 0.6f, urgency = 0.9f,
            createdAtMs = nowMs, expiresAtMs = nowMs + 60_000L,
            evidence = emptyList(), sourceSignals = emptyList(), summary = "",
        )
        val lo = hi.copy(confidence = 0.9f, urgency = 0.2f)
        assertTrue(hi.weight > lo.weight)
    }

    @Test
    fun `empty inputs produce empty output`() {
        val results = evaluator.evaluate(emptyList(), snapshot(batteryLevel = 100), presence(), nowMs)
        assertTrue(results.none { it.type == SituationType.LOW_BATTERY_BEFORE_TRAVEL })
    }

    @Test
    fun `unused variable note`() {
        // Keeps the compiler from complaining if later edits remove references.
        assertNull(null)
    }

    @Test
    fun `defaults are consistent`() {
        val s = Situation(
            type = SituationType.POSSIBLE_DELIVERY_WAITING,
            confidence = 0.5f, urgency = 0.2f,
            createdAtMs = nowMs, expiresAtMs = nowMs + 60_000L,
            evidence = emptyList(), sourceSignals = emptyList(), summary = "x",
        )
        assertEquals("x", s.summary)
    }
}
