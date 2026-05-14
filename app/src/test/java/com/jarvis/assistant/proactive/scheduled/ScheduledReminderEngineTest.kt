package com.jarvis.assistant.proactive.scheduled

import com.jarvis.assistant.proactive.ProactiveEvent
import com.jarvis.assistant.proactive.ProactiveEventType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * ScheduledReminderEngineTest — covers the diff / dedupe / offset
 * scheduling / cancellation logic.  Pure JVM — no Android.
 */
class ScheduledReminderEngineTest {

    private val fixedTz = TimeZone.getTimeZone("UTC")
    private val SETTINGS_DEFAULT = ScheduledReminderSettings.DEFAULT

    /** Fake source whose return value the test can swap per-call. */
    private class FakeSource(
        override val source: ScheduledReminderSource,
        var items: List<ScheduledReminderItem> = emptyList(),
    ) : ScheduledReminderItemSource {
        override suspend fun fetchUpcoming(nowMs: Long, lookAheadMs: Long) = items
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun mkEngine(
        source: ScheduledReminderItemSource,
        nowMs: () -> Long,
        sink: (ProactiveEvent) -> Unit,
        settings: () -> ScheduledReminderSettings = { SETTINGS_DEFAULT },
    ): ScheduledReminderEngine = ScheduledReminderEngine(
        eventSink        = sink,
        sources          = listOf(source),
        settingsProvider = settings,
        clock            = nowMs,
        tz               = { fixedTz },
    )

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test fun `calendar event schedules 30 + 10 minute instances`() = runBlocking {
        var now = 1_000L
        val src = FakeSource(ScheduledReminderSource.CALENDAR, items = listOf(
            ScheduledReminderItem(
                source = ScheduledReminderSource.CALENDAR,
                sourceId = "evt1",
                title = "Dentist",
                startMs = 1_000L + 60 * 60_000L,        // +1 hour
            ),
        ))
        val fired = mutableListOf<ProactiveEvent>()
        val engine = mkEngine(src, nowMs = { now }, sink = { fired += it })
        engine.runOnce()

        val instances = engine.store.snapshot()
        assertEquals(2, instances.size)
        assertTrue(instances.any { it.offsetMinutes == 30 })
        assertTrue(instances.any { it.offsetMinutes == 10 })
        assertTrue("no events should have fired yet", fired.isEmpty())
    }

    @Test fun `30m event fires CALENDAR_EVENT_30M`() = runBlocking {
        var now = 1_000L
        val startMs = now + 30 * 60_000L    // exactly 30m away → 30m offset due now
        val src = FakeSource(ScheduledReminderSource.CALENDAR, items = listOf(
            ScheduledReminderItem(ScheduledReminderSource.CALENDAR, "e", "Dentist", startMs)
        ))
        val fired = mutableListOf<ProactiveEvent>()
        val engine = mkEngine(src, { now }, { fired += it })
        engine.runOnce()
        assertEquals(1, fired.count { it.type == ProactiveEventType.CALENDAR_EVENT_30M })
        // 10m offset isn't due yet
        assertNull(fired.firstOrNull { it.type == ProactiveEventType.CALENDAR_EVENT_10M })
    }

    @Test fun `10m event fires CALENDAR_EVENT_10M`() = runBlocking {
        var now = 1_000L
        val startMs = now + 10 * 60_000L
        val src = FakeSource(ScheduledReminderSource.CALENDAR, items = listOf(
            ScheduledReminderItem(ScheduledReminderSource.CALENDAR, "e", "Dentist", startMs)
        ))
        val fired = mutableListOf<ProactiveEvent>()
        val engine = mkEngine(src, { now }, { fired += it })
        engine.runOnce()
        assertEquals(1, fired.count { it.type == ProactiveEventType.CALENDAR_EVENT_10M })
    }

    @Test fun `changed event reschedules`() = runBlocking {
        var now = 1_000L
        val src = FakeSource(ScheduledReminderSource.CALENDAR, items = listOf(
            ScheduledReminderItem(ScheduledReminderSource.CALENDAR, "e", "Dentist",
                startMs = now + 60 * 60_000L)
        ))
        val engine = mkEngine(src, { now }, sink = { })
        engine.runOnce()
        val before = engine.store.snapshot().first { it.offsetMinutes == 30 }

        // Item moves later by 30 minutes
        src.items = listOf(src.items[0].copy(
            startMs    = now + 90 * 60_000L,
            fingerprint = "${now + 90 * 60_000L}:Dentist",
        ))
        engine.runOnce()
        val after = engine.store.snapshot().first { it.offsetMinutes == 30 }
        assertTrue("scheduledAt should have shifted by 30m, " +
            "before=${before.scheduledAtMs} after=${after.scheduledAtMs}",
            after.scheduledAtMs - before.scheduledAtMs == 30 * 60_000L)
    }

    @Test fun `deleted event cancels reminders`() = runBlocking {
        var now = 1_000L
        val src = FakeSource(ScheduledReminderSource.CALENDAR, items = listOf(
            ScheduledReminderItem(ScheduledReminderSource.CALENDAR, "e", "Dentist",
                startMs = now + 60 * 60_000L)
        ))
        val engine = mkEngine(src, { now }, sink = { })
        engine.runOnce()
        assertEquals(2, engine.store.snapshot().size)
        src.items = emptyList()
        engine.runOnce()
        assertEquals(0, engine.store.snapshot().size)
    }

    @Test fun `todoist task with no datetime is skipped`() = runBlocking {
        // The TodoistReminderSource itself filters; we exercise that
        // the engine doesn't try to schedule instances for items it
        // doesn't receive — empty source → empty store.
        val src = FakeSource(ScheduledReminderSource.TODOIST, items = emptyList())
        val fired = mutableListOf<ProactiveEvent>()
        val engine = mkEngine(src, { 1_000L }, { fired += it })
        engine.runOnce()
        assertTrue(engine.store.snapshot().isEmpty())
        assertTrue(fired.isEmpty())
    }

    @Test fun `category-disabled offset does not fire`() = runBlocking {
        var now = 1_000L
        val startMs = now + 10 * 60_000L
        val src = FakeSource(ScheduledReminderSource.CALENDAR, items = listOf(
            ScheduledReminderItem(ScheduledReminderSource.CALENDAR, "e", "Dentist", startMs)
        ))
        val fired = mutableListOf<ProactiveEvent>()
        val engine = mkEngine(src, { now }, { fired += it },
            settings = { SETTINGS_DEFAULT.copy(offset10mEnabled = false) })
        engine.runOnce()
        assertTrue("10m disabled → no fire", fired.isEmpty())
    }

    @Test fun `late reminder beyond tolerance is skipped`() = runBlocking {
        var now = 1_000L
        // Item start is in 30m, so the 30m offset's scheduledAt = now.
        // First tick fires it.
        val startMs = now + 30 * 60_000L
        val src = FakeSource(ScheduledReminderSource.CALENDAR, items = listOf(
            ScheduledReminderItem(ScheduledReminderSource.CALENDAR, "e", "Dentist", startMs)
        ))
        val fired = mutableListOf<ProactiveEvent>()
        val engine = mkEngine(src, { now }, { fired += it })
        // Skip past the 30m offset by 5 minutes — well beyond tolerance.
        now += 5 * 60_000L
        engine.runOnce()
        // Only the 10m instance remains as schedulable now; the 30m one
        // got marked fired-without-dispatch due to late skip.
        val firedTypes = fired.map { it.type }
        assertFalse("30m should NOT have fired late",
            firedTypes.contains(ProactiveEventType.CALENDAR_EVENT_30M))
    }

    @Test fun `phrase builder renders 30m and 10m templates`() {
        val t = 1_700_000_000_000L
        val s30 = ScheduledReminderPhraseBuilder.build(t, 30, "football training",
            timeZone = fixedTz)
        val s10 = ScheduledReminderPhraseBuilder.build(t, 10, "dentist",
            timeZone = fixedTz)
        assertTrue("'$s30' should be a heads-up sentence",
            s30.startsWith("Don't forget,"))
        assertTrue("'$s10' should be a 'in 10 minutes' sentence",
            s10.contains("10 minutes"))
    }

    @Test fun `phrase builder handles noon and midnight`() {
        // 12:00 UTC on a known date.
        val cal = java.util.Calendar.getInstance(fixedTz).apply {
            timeInMillis = 0
            set(2026, 4, 14, 12, 0, 0)
        }
        val noon = ScheduledReminderPhraseBuilder.formatTimeOfDay(cal.timeInMillis, fixedTz)
        assertEquals("noon", noon)
        cal.set(2026, 4, 14, 0, 0, 0)
        val mid = ScheduledReminderPhraseBuilder.formatTimeOfDay(cal.timeInMillis, fixedTz)
        assertEquals("midnight", mid)
    }

    @Test fun `dedupe key is stable per source + offset`() {
        val a = ScheduledReminderInstance(
            ScheduledReminderSource.CALENDAR, "evt-1", "Dentist",
            scheduledAtMs = 0, itemTimeMs = 0, offsetMinutes = 30, fingerprint = "x")
        val b = a.copy(offsetMinutes = 10)
        assertNotNull(a.dedupeKey)
        assertTrue(a.dedupeKey != b.dedupeKey)
    }

    @Test fun `dispatch bridge downgrades to passive when idle`() {
        val bridge = ScheduledReminderDispatchBridge(
            dispatcher        = NopDispatcher,
            settingsProvider  = { SETTINGS_DEFAULT.copy(backgroundSpeechEnabled = false) },
            lastInteractionMs = { null },   // never interacted
            clock             = { 1_000L },
        )
        val event = ProactiveEvent(
            type = ProactiveEventType.CALENDAR_EVENT_30M,
            title = "Dentist",
            spokenText = "Don't forget, dentist is at 3.",
            urgency = 0.7f, relevance = 0.9f, confidence = 1f, annoyanceCost = 0.2f,
            dedupeKey = "sched:calendar:1:30",
        )
        val action = bridge.toAction(event)
        assertTrue("idle + no bg speech → PassiveAction",
            action is com.jarvis.assistant.proactive.ProactiveAction.PassiveAction)
    }

    @Test fun `dispatch bridge speaks when active`() {
        val bridge = ScheduledReminderDispatchBridge(
            dispatcher        = NopDispatcher,
            settingsProvider  = { SETTINGS_DEFAULT.copy(backgroundSpeechEnabled = false) },
            lastInteractionMs = { 1_000L },     // just interacted
            clock             = { 2_000L },
        )
        val event = ProactiveEvent(
            type = ProactiveEventType.CALENDAR_EVENT_10M,
            title = "Dentist",
            spokenText = "Dentist starts in 10 minutes.",
            urgency = 0.85f, relevance = 0.9f, confidence = 1f, annoyanceCost = 0.1f,
            dedupeKey = "sched:calendar:1:10",
        )
        val action = bridge.toAction(event)
        assertTrue("recent interaction → SpeakAction",
            action is com.jarvis.assistant.proactive.ProactiveAction.SpeakAction)
    }

    private object NopDispatcher : com.jarvis.assistant.proactive.ProactiveDispatcher {
        override suspend fun dispatch(action: com.jarvis.assistant.proactive.ProactiveAction) = Unit
    }
}
