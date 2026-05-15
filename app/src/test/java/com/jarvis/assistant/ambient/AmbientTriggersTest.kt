package com.jarvis.assistant.ambient

import com.jarvis.assistant.core.context.AgentContextFactory
import com.jarvis.assistant.core.decisions.triggers.AmbientCalendarTravelTrigger
import com.jarvis.assistant.core.decisions.triggers.AmbientCarBluetoothTrigger
import com.jarvis.assistant.core.decisions.triggers.AmbientEtsyCustomerNudgeTrigger
import com.jarvis.assistant.core.decisions.triggers.AmbientHaDeviceRunningAwayTrigger
import com.jarvis.assistant.core.decisions.triggers.AmbientLocationTodoistTrigger
import com.jarvis.assistant.proactive.ContextSnapshot
import com.jarvis.assistant.proactive.ProactiveEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the 5 Ambient Intelligence triggers and AmbientContextScorer.
 *
 * Tests verify:
 *   - Correct match / no-match conditions for every trigger
 *   - No LLM call is made (all logic is pure functions)
 *   - Quiet-hours suppression and cooldown are not tested here — that is
 *     ProactiveEngine's responsibility
 */
class AmbientTriggersTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val BASE_NOW_MS = 1_700_000_000_000L

    private fun baseSnapshot(
        nowMs: Long = BASE_NOW_MS,
        unreadNotifications: Int = 0,
        nextMeetingMs: Long? = null,
        nextMeetingTitle: String? = null,
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
        unreadNotificationCount       = unreadNotifications,
        lastNotificationText          = null,
        lastNotificationApp           = null,
        nextMeetingAtMillis           = nextMeetingMs,
        nextMeetingTitle              = nextMeetingTitle,
    )

    private fun ctx(
        snapshot: ContextSnapshot = baseSnapshot(),
        ambient: AmbientContext = AmbientContext.EMPTY,
    ) = AgentContextFactory.fromSnapshot(snapshot, ambient)

    // ── AmbientEtsyCustomerNudgeTrigger ──────────────────────────────────────

    @Test
    fun `etsy trigger - no match when app not open`() {
        val trigger = AmbientEtsyCustomerNudgeTrigger()
        val ambient = AmbientContext(unreadCustomerMessages = 3)
        assertNull(trigger.match(ctx(ambient = ambient), emptyList()))
    }

    @Test
    fun `etsy trigger - no match when app open but no messages`() {
        val trigger = AmbientEtsyCustomerNudgeTrigger()
        val ambient = AmbientContext(
            recentAppOpens        = listOf(RecentAppOpen("com.etsy.android", BASE_NOW_MS - 60_000L)),
            unreadCustomerMessages = 0,
        )
        val c = trigger.match(ctx(ambient = ambient), emptyList())
        assertNull(c)
    }

    @Test
    fun `etsy trigger - fires when etsy open within window with customer messages`() {
        val trigger = AmbientEtsyCustomerNudgeTrigger()
        val ambient = AmbientContext(
            recentAppOpens        = listOf(RecentAppOpen("com.etsy.android", BASE_NOW_MS - 60_000L)),
            unreadCustomerMessages = 2,
        )
        val c = trigger.match(ctx(ambient = ambient), emptyList())
        assertNotNull(c)
        assertEquals(ProactiveEventType.AMBIENT_APP_CONTEXT_NUDGE, c!!.eventType)
        assertTrue(c.spokenText.contains("Etsy"))
        assertTrue(c.spokenText.contains("2 messages"))
    }

    @Test
    fun `etsy trigger - no match when app was opened more than 10 minutes ago`() {
        val trigger = AmbientEtsyCustomerNudgeTrigger()
        val staleOpen = BASE_NOW_MS - (11 * 60_000L)
        val ambient = AmbientContext(
            recentAppOpens        = listOf(RecentAppOpen("com.etsy.android", staleOpen)),
            unreadCustomerMessages = 5,
        )
        assertNull(trigger.match(ctx(ambient = ambient), emptyList()))
    }

    @Test
    fun `etsy trigger - fires via notification count when customer messages is zero`() {
        val trigger = AmbientEtsyCustomerNudgeTrigger()
        val ambient = AmbientContext(
            recentAppOpens = listOf(RecentAppOpen("com.etsy.android", BASE_NOW_MS - 30_000L)),
            unreadCustomerMessages = 0,
        )
        val snapshot = baseSnapshot(unreadNotifications = 3)
        val c = trigger.match(ctx(snapshot = snapshot, ambient = ambient), emptyList())
        assertNotNull(c)
        assertTrue(c!!.spokenText.contains("3 messages"))
    }

    // ── AmbientCarBluetoothTrigger ────────────────────────────────────────────

    @Test
    fun `car bt trigger - no match when car not connected`() {
        val trigger = AmbientCarBluetoothTrigger()
        val snapshot = baseSnapshot(nextMeetingMs = BASE_NOW_MS + 60 * 60_000L)
        assertNull(trigger.match(ctx(snapshot = snapshot), emptyList()))
    }

    @Test
    fun `car bt trigger - no match when no upcoming meeting`() {
        val trigger = AmbientCarBluetoothTrigger()
        val ambient = AmbientContext(carBtConnectedMs = BASE_NOW_MS - 60_000L)
        assertNull(trigger.match(ctx(ambient = ambient), emptyList()))
    }

    @Test
    fun `car bt trigger - fires when car connected within 5 min and meeting in 90 min`() {
        val trigger = AmbientCarBluetoothTrigger()
        val meetingMs = BASE_NOW_MS + 90 * 60_000L
        val ambient  = AmbientContext(carBtConnectedMs = BASE_NOW_MS - 60_000L)
        val snapshot = baseSnapshot(nextMeetingMs = meetingMs, nextMeetingTitle = "Football")
        val c = trigger.match(ctx(snapshot = snapshot, ambient = ambient), emptyList())
        assertNotNull(c)
        assertEquals(ProactiveEventType.AMBIENT_TRAVEL_SUGGESTION, c!!.eventType)
        assertTrue(c.spokenText.contains("Football"))
    }

    @Test
    fun `car bt trigger - no match when connected more than 5 min ago`() {
        val trigger = AmbientCarBluetoothTrigger()
        val ambient = AmbientContext(carBtConnectedMs = BASE_NOW_MS - (6 * 60_000L))
        val snapshot = baseSnapshot(nextMeetingMs = BASE_NOW_MS + 60 * 60_000L)
        assertNull(trigger.match(ctx(snapshot = snapshot, ambient = ambient), emptyList()))
    }

    @Test
    fun `car bt trigger - no match when meeting more than 3 hours away`() {
        val trigger = AmbientCarBluetoothTrigger()
        val ambient = AmbientContext(carBtConnectedMs = BASE_NOW_MS - 60_000L)
        val snapshot = baseSnapshot(nextMeetingMs = BASE_NOW_MS + 200 * 60_000L)
        assertNull(trigger.match(ctx(snapshot = snapshot, ambient = ambient), emptyList()))
    }

    // ── AmbientCalendarTravelTrigger ──────────────────────────────────────────

    @Test
    fun `calendar travel trigger - no match when no meeting`() {
        val trigger = AmbientCalendarTravelTrigger()
        assertNull(trigger.match(ctx(), emptyList()))
    }

    @Test
    fun `calendar travel trigger - fires when within window of default leave time`() {
        val trigger = AmbientCalendarTravelTrigger()
        // Default lead = 20 min, window = 5 min
        // Meeting in 22 min → leave in 2 min → within ±5 min window → fires
        val snapshot = baseSnapshot(
            nextMeetingMs    = BASE_NOW_MS + 22 * 60_000L,
            nextMeetingTitle = "Team meeting",
        )
        val c = trigger.match(ctx(snapshot = snapshot), emptyList())
        assertNotNull(c)
        assertEquals(ProactiveEventType.AMBIENT_ROUTINE_SUGGESTION, c!!.eventType)
        assertTrue(c.spokenText.contains("Team meeting"))
        assertEquals(0.55f, c.confidence, 0.001f) // no learned pattern
    }

    @Test
    fun `calendar travel trigger - fires with higher confidence when learned pattern present`() {
        val trigger = AmbientCalendarTravelTrigger()
        // Learned lead = 15 min, window = 5 min
        // Meeting in 18 min → leave in 3 min → within ±5 min window → fires
        val snapshot = baseSnapshot(nextMeetingMs = BASE_NOW_MS + 18 * 60_000L)
        val ambient  = AmbientContext(learnedLeaveLeadMinutes = 15)
        val c = trigger.match(ctx(snapshot = snapshot, ambient = ambient), emptyList())
        assertNotNull(c)
        assertEquals(0.75f, c!!.confidence, 0.001f) // learned pattern
    }

    @Test
    fun `calendar travel trigger - no match when meeting too far away`() {
        val trigger = AmbientCalendarTravelTrigger()
        // Meeting in 60 min → leave in 40 min → outside ±5 min window
        val snapshot = baseSnapshot(nextMeetingMs = BASE_NOW_MS + 60 * 60_000L)
        assertNull(trigger.match(ctx(snapshot = snapshot), emptyList()))
    }

    // ── AmbientHaDeviceRunningAwayTrigger ─────────────────────────────────────

    @Test
    fun `ha device trigger - no match when no devices running`() {
        val trigger = AmbientHaDeviceRunningAwayTrigger()
        val ambient = AmbientContext(
            locationBucket         = AmbientLocationBucket.SHOP,
            haDevicesRunningAway   = emptyList(),
        )
        assertNull(trigger.match(ctx(ambient = ambient), emptyList()))
    }

    @Test
    fun `ha device trigger - no match when devices running but location is home`() {
        val trigger = AmbientHaDeviceRunningAwayTrigger()
        val ambient = AmbientContext(
            locationBucket       = AmbientLocationBucket.HOME,
            haDevicesRunningAway = listOf("Printer"),
        )
        assertNull(trigger.match(ctx(ambient = ambient), emptyList()))
    }

    @Test
    fun `ha device trigger - fires when device running and user is at shop`() {
        val trigger = AmbientHaDeviceRunningAwayTrigger()
        val ambient = AmbientContext(
            locationBucket       = AmbientLocationBucket.SHOP,
            haDevicesRunningAway = listOf("Printer"),
        )
        val c = trigger.match(ctx(ambient = ambient), emptyList())
        assertNotNull(c)
        assertEquals(ProactiveEventType.AMBIENT_HOME_ASSISTANT_ALERT, c!!.eventType)
        assertTrue(c.spokenText.contains("Printer"))
        assertTrue(c.urgency >= 0.60f)
    }

    @Test
    fun `ha device trigger - urgency at least 0_60 even with low scorer output`() {
        val trigger = AmbientHaDeviceRunningAwayTrigger()
        val ambient = AmbientContext(
            locationBucket       = AmbientLocationBucket.TRANSIT,
            haDevicesRunningAway = listOf("Workshop socket"),
        )
        val c = trigger.match(ctx(ambient = ambient), emptyList())
        assertNotNull(c)
        assertTrue("urgency must be >= 0.60", c!!.urgency >= 0.60f)
    }

    @Test
    fun `ha device trigger - multiple devices joined in speech text`() {
        val trigger = AmbientHaDeviceRunningAwayTrigger()
        val ambient = AmbientContext(
            locationBucket       = AmbientLocationBucket.WORK,
            haDevicesRunningAway = listOf("Printer", "Kettle"),
        )
        val c = trigger.match(ctx(ambient = ambient), emptyList())
        assertNotNull(c)
        assertTrue(c!!.spokenText.contains("Printer"))
        assertTrue(c.spokenText.contains("Kettle"))
    }

    // ── AmbientLocationTodoistTrigger ─────────────────────────────────────────

    @Test
    fun `todoist location trigger - no match when not near shop`() {
        val trigger = AmbientLocationTodoistTrigger()
        val ambient = AmbientContext(todoistItemsMatchingLocation = listOf("Milk"))
        assertNull(trigger.match(ctx(ambient = ambient), emptyList()))
    }

    @Test
    fun `todoist location trigger - no match when near shop but no tasks`() {
        val trigger = AmbientLocationTodoistTrigger()
        val ambient = AmbientContext(nearShopName = "Tesco")
        assertNull(trigger.match(ctx(ambient = ambient), emptyList()))
    }

    @Test
    fun `todoist location trigger - fires when near shop with matching tasks`() {
        val trigger = AmbientLocationTodoistTrigger()
        val ambient = AmbientContext(
            nearShopName                  = "Tesco",
            todoistItemsMatchingLocation  = listOf("Milk"),
        )
        val c = trigger.match(ctx(ambient = ambient), emptyList())
        assertNotNull(c)
        assertEquals(ProactiveEventType.AMBIENT_LOCATION_TODOIST_MATCH, c!!.eventType)
        assertTrue(c.spokenText.contains("Tesco"))
        assertTrue(c.spokenText.contains("Milk"))
    }

    @Test
    fun `todoist location trigger - multiple items joined in speech`() {
        val trigger = AmbientLocationTodoistTrigger()
        val ambient = AmbientContext(
            nearShopName                 = "Sainsbury's",
            todoistItemsMatchingLocation = listOf("Milk", "Bread"),
        )
        val c = trigger.match(ctx(ambient = ambient), emptyList())
        assertNotNull(c)
        assertTrue(c!!.spokenText.contains("Milk"))
        assertTrue(c.spokenText.contains("Bread"))
    }

    @Test
    fun `todoist location trigger - dedupe key uses shop name and hour bucket`() {
        val trigger = AmbientLocationTodoistTrigger()
        val ambient = AmbientContext(
            nearShopName                 = "Aldi",
            todoistItemsMatchingLocation = listOf("Eggs"),
        )
        val c = trigger.match(ctx(ambient = ambient), emptyList())!!
        assertTrue(c.dedupeKey.startsWith("ambient_location_todoist_Aldi_"))
    }

    // ── AmbientContextScorer ──────────────────────────────────────────────────

    @Test
    fun `scorer - low confidence below 0_30 maps to NONE`() {
        val level = AmbientContextScorer.score(confidence = 0.20f)
        assertEquals(AmbientContextScorer.Level.NONE, level)
    }

    @Test
    fun `scorer - medium confidence maps to NOTIFY when below speak threshold`() {
        val level = AmbientContextScorer.score(confidence = 0.50f, minToSpeak = 0.65f)
        assertEquals(AmbientContextScorer.Level.NOTIFY, level)
    }

    @Test
    fun `scorer - high confidence maps to SPEAK when above speak threshold`() {
        val level = AmbientContextScorer.score(confidence = 0.80f, minToSpeak = 0.65f)
        assertEquals(AmbientContextScorer.Level.SPEAK, level)
    }

    @Test
    fun `scorer - dismissals reduce effective confidence`() {
        // At 0.70f with 3 dismissals: effective = 0.70 * (1 - 0.3) = 0.49 → NOTIFY
        val level = AmbientContextScorer.score(confidence = 0.70f, minToSpeak = 0.65f, dismissalCount = 3)
        assertTrue(level != AmbientContextScorer.Level.SPEAK)
    }

    @Test
    fun `scorer - uncertain flag forces NOTIFY even when confidence is high`() {
        val level = AmbientContextScorer.score(confidence = 0.90f, isUncertain = true)
        assertEquals(AmbientContextScorer.Level.NOTIFY, level)
    }

    @Test
    fun `scorer - toScores returns urgency and annoyance pair`() {
        val (urgency, annoyance) = AmbientContextScorer.toScores(AmbientContextScorer.Level.SPEAK)
        assertTrue(urgency > 0f)
        assertTrue(annoyance >= 0f)
    }

    @Test
    fun `scorer - NONE level gives zero urgency`() {
        val (urgency, _) = AmbientContextScorer.toScores(AmbientContextScorer.Level.NONE)
        assertEquals(0f, urgency, 0.001f)
    }
}
