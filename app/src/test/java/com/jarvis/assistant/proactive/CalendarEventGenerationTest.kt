package com.jarvis.assistant.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * Unit tests for the calendar proactive signals added to [EventGenerator]
 * and [DecisionEngine].  No Android dependencies — the tests drive
 * [ContextSnapshot] directly.
 */
class CalendarEventGenerationTest {

    private val config = ProactiveConfig(
        quietHoursStartHour = 22,
        quietHoursEndHour = 7
    )
    private lateinit var cooldownStore: CooldownStore
    private lateinit var generator: EventGenerator
    private lateinit var scorer: EventScorer
    private lateinit var decisionEngine: DecisionEngine

    @Before
    fun setUp() {
        cooldownStore = CooldownStore()
        generator = EventGenerator(config)
        scorer = EventScorer(config, cooldownStore)
        decisionEngine = DecisionEngine(config, cooldownStore)
    }

    private fun snapshot(
        nowMs: Long = System.currentTimeMillis(),
        nextMeetingAt: Long? = null,
        nextMeetingTitle: String? = null,
        meetingsToday: Int = 0
    ) = ContextSnapshot(
        currentTimeMillis             = nowMs,
        batteryLevel                  = 80,
        isCharging                    = false,
        screenOn                      = true,
        isJarvisSpeaking              = false,
        isJarvisListening             = false,
        lastUserInteractionTimeMillis = null,
        activeReminderCount           = 0,
        nextReminderAtMillis          = null,
        missedCallsCount              = 0,
        lastMissedCallAtMillis        = null,
        lastMissedCallContactName     = null,
        currentLocationName           = null,
        networkAvailable              = true,
        nextMeetingAtMillis           = nextMeetingAt,
        nextMeetingTitle              = nextMeetingTitle,
        meetingsTodayCount            = meetingsToday
    )

    @Test
    fun noMeetingProducesNoCalendarEvents() {
        val events = generator.generate(snapshot())
        assertTrue(
            "Expected no calendar events, got $events",
            events.none {
                it.type == ProactiveEventType.UPCOMING_MEETING ||
                    it.type == ProactiveEventType.MEETING_STARTING_SOON ||
                    it.type == ProactiveEventType.DAILY_AGENDA
            }
        )
    }

    @Test
    fun upcomingMeetingTwelveMinutesOutEmitsPassiveTier() {
        val now = System.currentTimeMillis()
        val startMs = now + 12 * 60_000L
        val events = generator.generate(
            snapshot(now, nextMeetingAt = startMs, nextMeetingTitle = "Design review")
        )
        val meeting = events.firstOrNull { it.type == ProactiveEventType.UPCOMING_MEETING }
        assertNotNull("Expected UPCOMING_MEETING event", meeting)
        // 12 min out → urgency 0.55 (below ACTIVE threshold of 0.80)
        assertEquals(0.55f, meeting!!.urgency, 0.001f)
        val bucket = startMs / 60_000L * 60_000L
        assertEquals("upcoming_meeting_$bucket", meeting.dedupeKey)
    }

    @Test
    fun upcomingMeetingThreeMinutesOutEmitsActiveTier() {
        val now = System.currentTimeMillis()
        val startMs = now + 3 * 60_000L
        val events = generator.generate(
            snapshot(now, nextMeetingAt = startMs, nextMeetingTitle = "Design review")
        )
        val meeting = events.firstOrNull { it.type == ProactiveEventType.UPCOMING_MEETING }
        assertNotNull(meeting)
        assertTrue(meeting!!.spokenText!!.contains("3 minutes"))
        assertEquals(0.80f, meeting.urgency, 0.001f)
    }

    @Test
    fun meetingStartingSoonBypassesPresenceGate() {
        val now = System.currentTimeMillis()
        val startMs = now + 60_000L
        val snap = snapshot(now, nextMeetingAt = startMs, nextMeetingTitle = "Standup")
        val scored = scorer.scoreAll(generator.generate(snap), snap)
        val action = decisionEngine.decide(scored, snap)
        assertTrue(
            "Expected SpeakAction for imminent meeting, got $action",
            action is ProactiveAction.SpeakAction
        )
        assertEquals(
            ProactiveEventType.MEETING_STARTING_SOON,
            (action as ProactiveAction.SpeakAction).sourceType
        )
    }

    @Test
    fun meetingInPastMoreThanThirtySecondsDoesNotFire() {
        val now = System.currentTimeMillis()
        val startMs = now - 2 * 60_000L
        val events = generator.generate(snapshot(now, nextMeetingAt = startMs))
        assertTrue(
            events.none {
                it.type == ProactiveEventType.UPCOMING_MEETING ||
                    it.type == ProactiveEventType.MEETING_STARTING_SOON
            }
        )
    }

    @Test
    fun dailyAgendaFiresInMorningWindowWithMeetings() {
        val now = morningTimestamp(hour = 8)
        val events = generator.generate(
            snapshot(
                nowMs = now,
                nextMeetingTitle = "Kickoff",
                meetingsToday = 3
            )
        )
        val agenda = events.firstOrNull { it.type == ProactiveEventType.DAILY_AGENDA }
        assertNotNull("Expected DAILY_AGENDA at 08:00 with 3 meetings", agenda)
        assertTrue(agenda!!.spokenText!!.contains("3 meetings"))
    }

    @Test
    fun dailyAgendaDoesNotFireInAfternoon() {
        val now = morningTimestamp(hour = 14)
        val events = generator.generate(snapshot(nowMs = now, meetingsToday = 3))
        assertTrue(events.none { it.type == ProactiveEventType.DAILY_AGENDA })
    }

    @Test
    fun dedupeKeyStableWithinSameMinute() {
        val now = System.currentTimeMillis()
        val startMs = now + 5 * 60_000L
        val a = generator.generate(snapshot(now, nextMeetingAt = startMs))
            .first { it.type == ProactiveEventType.UPCOMING_MEETING }
        val b = generator.generate(snapshot(now + 30_000L, nextMeetingAt = startMs))
            .first { it.type == ProactiveEventType.UPCOMING_MEETING }
        assertEquals(a.dedupeKey, b.dedupeKey)
    }

    @Test
    fun sanitiseTitleStripsUrlAndPhone() {
        val cleaned = AppCalendarContextSource.sanitiseTitle(
            "Call with Mike https://zoom.us/j/12345 +1-555-123-4567"
        )
        assertEquals("Call with Mike", cleaned)
    }

    @Test
    fun sanitiseTitleSuppressesPrivateKeyword() {
        assertNull(AppCalendarContextSource.sanitiseTitle("Private appointment"))
    }

    @Test
    fun meetingStartingSoonBypassesQuietHours() {
        val nightNow = nightTimestamp(hour = 6)
        val startMs = nightNow + 60_000L
        val snap = snapshot(nightNow, nextMeetingAt = startMs, nextMeetingTitle = "Early standup")
        val scored = scorer.scoreAll(generator.generate(snap), snap)
        val action = decisionEngine.decide(scored, snap)
        assertTrue(
            "Expected imminent meeting to bypass quiet hours at 06:00",
            action is ProactiveAction.SpeakAction
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun morningTimestamp(hour: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 15)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun nightTimestamp(hour: Int): Long = morningTimestamp(hour)
}
