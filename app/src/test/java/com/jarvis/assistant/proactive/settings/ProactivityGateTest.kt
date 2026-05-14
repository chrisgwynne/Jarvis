package com.jarvis.assistant.proactive.settings

import com.jarvis.assistant.proactive.ProactiveAction
import com.jarvis.assistant.proactive.ProactiveEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ProactivityGateTest {

    /** Helper — speak-action factory. */
    private fun speak(
        type: ProactiveEventType = ProactiveEventType.UNREAD_NOTIFICATION,
        text: String = "test",
        dedupe: String = "k",
    ) = ProactiveAction.SpeakAction(text, dedupe, type)

    private fun passive(
        type: ProactiveEventType = ProactiveEventType.UNREAD_NOTIFICATION,
        title: String = "test",
        dedupe: String = "k",
    ) = ProactiveAction.PassiveAction(title, body = null, dedupeKey = dedupe, sourceType = type)

    private fun gate(
        settings: ProactivitySettings,
        nowMs: Long = 12 * 60 * 60_000L,            // noon UTC by default
        msSinceLastSurface: Long = Long.MAX_VALUE,
    ) = ProactivityGate(
        settingsProvider         = { settings },
        msSinceLastGlobalSurface = { msSinceLastSurface },
        clock                    = { nowMs },
        timeZone                 = { TimeZone.getTimeZone("UTC") },
    )

    private val enabled = ProactivitySettings.DEFAULT.copy(
        enabled = true,
        interruptionMode = InterruptionMode.SPEAK_ANYTIME,
        // Disable quiet hours by default so individual tests opt in.
        quietHoursEnabled = false,
        globalCooldownMinutes = 0,
    )

    // ── 1. Master disabled suppresses everything ──────────────────────────

    @Test
    fun `master disabled suppresses speak`() {
        val g = gate(enabled.copy(enabled = false))
        val v = g.evaluate(speak(), lastUserInteractionMs = null)
        assertTrue(v is ProactivityGate.Verdict.Suppress)
        assertEquals("master_disabled",
            (v as ProactivityGate.Verdict.Suppress).reason)
    }

    @Test
    fun `master disabled suppresses passive`() {
        val g = gate(enabled.copy(enabled = false))
        val v = g.evaluate(passive(), lastUserInteractionMs = null)
        assertTrue(v is ProactivityGate.Verdict.Suppress)
    }

    @Test
    fun `master disabled suppresses NoAction too`() {
        val g = gate(enabled.copy(enabled = false))
        val v = g.evaluate(ProactiveAction.NoAction.DEFAULT, null)
        // NoAction is logically already a "do nothing"; semantically the gate
        // suppresses it too — caller never surfaces NoAction either way.
        assertTrue(v is ProactivityGate.Verdict.Suppress)
    }

    // ── 2. Quiet hours ─────────────────────────────────────────────────────

    @Test
    fun `quiet hours suppress normal events`() {
        val s = enabled.copy(
            quietHoursEnabled = true,
            quietStartMinute = 22 * 60,
            quietEndMinute   = 7  * 60,
            allowUrgentDuringQuietHours = true,
        )
        // 02:00 UTC — inside 22:00→07:00 wrap.
        val now = 2 * 60 * 60_000L
        val v = gate(s, nowMs = now).evaluate(
            speak(ProactiveEventType.UNREAD_NOTIFICATION), null
        )
        assertTrue(v is ProactivityGate.Verdict.Suppress)
        assertTrue((v as ProactivityGate.Verdict.Suppress).reason.startsWith("quiet_hours"))
    }

    @Test
    fun `urgent event breaks quiet hours when allowed`() {
        val s = enabled.copy(
            quietHoursEnabled = true,
            quietStartMinute = 22 * 60,
            quietEndMinute   = 7  * 60,
            allowUrgentDuringQuietHours = true,
        )
        val now = 2 * 60 * 60_000L
        // LOW_BATTERY is in the urgent list.
        val v = gate(s, nowMs = now).evaluate(
            speak(ProactiveEventType.LOW_BATTERY), null
        )
        assertTrue("expected Allow, got $v", v is ProactivityGate.Verdict.Allow)
    }

    @Test
    fun `urgent override off keeps urgent events suppressed`() {
        val s = enabled.copy(
            quietHoursEnabled = true,
            quietStartMinute = 22 * 60,
            quietEndMinute   = 7  * 60,
            allowUrgentDuringQuietHours = false,
        )
        val now = 2 * 60 * 60_000L
        val v = gate(s, nowMs = now).evaluate(
            speak(ProactiveEventType.LOW_BATTERY), null
        )
        assertTrue(v is ProactivityGate.Verdict.Suppress)
    }

    @Test
    fun `quiet hours non-wrapping window`() {
        // 13:00–14:00 — doesn't wrap.
        val s = enabled.copy(
            quietHoursEnabled = true,
            quietStartMinute = 13 * 60,
            quietEndMinute   = 14 * 60,
        )
        val noon  = 12 * 60 * 60_000L
        val onPm  = 13L * 60 * 60_000L + 30 * 60_000L  // 13:30 UTC
        val twoPm = 14L * 60 * 60_000L + 1  * 60_000L  // 14:01 UTC
        assertTrue(gate(s, nowMs = noon).evaluate(speak(), null) is ProactivityGate.Verdict.Allow)
        assertTrue(gate(s, nowMs = onPm).evaluate(speak(), null) is ProactivityGate.Verdict.Suppress)
        assertTrue(gate(s, nowMs = twoPm).evaluate(speak(), null) is ProactivityGate.Verdict.Allow)
    }

    @Test
    fun `quiet hours wrap past midnight`() {
        val s = enabled.copy(
            quietHoursEnabled = true,
            quietStartMinute = 22 * 60,
            quietEndMinute   = 7  * 60,
        )
        // 23:00 UTC — inside window
        val late = 23L * 60 * 60_000L
        // 06:30 UTC — still inside (overnight wrap)
        val early = 6L  * 60 * 60_000L + 30 * 60_000L
        // 09:00 UTC — outside
        val nine = 9L  * 60 * 60_000L
        assertTrue(gate(s, nowMs = late ).evaluate(speak(), null) is ProactivityGate.Verdict.Suppress)
        assertTrue(gate(s, nowMs = early).evaluate(speak(), null) is ProactivityGate.Verdict.Suppress)
        assertTrue(gate(s, nowMs = nine ).evaluate(speak(), null) is ProactivityGate.Verdict.Allow)
    }

    // ── 3. Categories ──────────────────────────────────────────────────────

    @Test
    fun `category disabled suppresses matching event`() {
        val s = enabled.copy(remindersEnabled = false)
        val v = gate(s).evaluate(speak(ProactiveEventType.UPCOMING_REMINDER), null)
        assertTrue(v is ProactivityGate.Verdict.Suppress)
        assertTrue((v as ProactivityGate.Verdict.Suppress).reason.startsWith("category_disabled"))
    }

    @Test
    fun `category enabled lets matching event pass`() {
        val s = enabled.copy(remindersEnabled = true)
        val v = gate(s).evaluate(speak(ProactiveEventType.UPCOMING_REMINDER), null)
        assertTrue("expected Allow, got $v", v is ProactivityGate.Verdict.Allow)
    }

    // ── 4. Interruption mode ───────────────────────────────────────────────

    @Test
    fun `silent mode suppresses speak`() {
        val s = enabled.copy(interruptionMode = InterruptionMode.SILENT)
        val v = gate(s).evaluate(speak(), null)
        assertTrue(v is ProactivityGate.Verdict.Suppress)
        assertEquals("silent_mode", (v as ProactivityGate.Verdict.Suppress).reason)
    }

    @Test
    fun `notify-only mode downgrades speak to passive`() {
        val s = enabled.copy(interruptionMode = InterruptionMode.NOTIFY_ONLY)
        val v = gate(s).evaluate(speak(), null)
        assertTrue(v is ProactivityGate.Verdict.Downgrade)
    }

    @Test
    fun `speak-when-active downgrades when user is inactive`() {
        val s = enabled.copy(interruptionMode = InterruptionMode.SPEAK_WHEN_ACTIVE)
        val now = 1_000_000L
        // Last interaction 10 minutes ago, window is 5.
        val v = gate(s, nowMs = now).evaluate(speak(), lastUserInteractionMs = now - 10 * 60_000L)
        assertTrue(v is ProactivityGate.Verdict.Downgrade)
    }

    @Test
    fun `speak-when-active speaks when recently interacted`() {
        val s = enabled.copy(interruptionMode = InterruptionMode.SPEAK_WHEN_ACTIVE)
        val now = 1_000_000L
        val v = gate(s, nowMs = now).evaluate(speak(), lastUserInteractionMs = now - 30_000L)
        assertTrue("expected Allow, got $v", v is ProactivityGate.Verdict.Allow)
    }

    @Test
    fun `speak-anytime always allows outside quiet hours`() {
        val s = enabled.copy(interruptionMode = InterruptionMode.SPEAK_ANYTIME)
        val v = gate(s).evaluate(speak(), null)
        assertTrue(v is ProactivityGate.Verdict.Allow)
    }

    @Test
    fun `notify-only does not call TTS — passive action stays passive`() {
        val s = enabled.copy(interruptionMode = InterruptionMode.NOTIFY_ONLY)
        // A passive action is already passive; the mode is irrelevant for it
        // (only the speak->notify downgrade matters for that mode).
        val v = gate(s).evaluate(passive(), null)
        assertTrue("expected Allow on passive, got $v", v is ProactivityGate.Verdict.Allow)
    }

    // ── 5. Cooldown ────────────────────────────────────────────────────────

    @Test
    fun `cooldown suppresses repeated events`() {
        // Configured cooldown = 5 minutes; last surface was 1 second ago.
        val s = enabled.copy(globalCooldownMinutes = 5)
        val v = gate(s, msSinceLastSurface = 1_000L).evaluate(speak(), null)
        assertTrue(v is ProactivityGate.Verdict.Suppress)
        assertTrue((v as ProactivityGate.Verdict.Suppress).reason.startsWith("global_cooldown"))
    }

    @Test
    fun `cooldown allows after window elapses`() {
        // Configured cooldown = 5 minutes; last surface was 6 minutes ago.
        val s = enabled.copy(globalCooldownMinutes = 5)
        val v = gate(s, msSinceLastSurface = 6 * 60_000L).evaluate(speak(), null)
        assertTrue("expected Allow, got $v", v is ProactivityGate.Verdict.Allow)
    }

    @Test
    fun `cooldown of zero is disabled`() {
        // Cooldown = 0 minutes → gate never blocks on this axis.
        val s = enabled.copy(globalCooldownMinutes = 0)
        val v = gate(s, msSinceLastSurface = 0L).evaluate(speak(), null)
        assertTrue(v is ProactivityGate.Verdict.Allow)
    }

    // ── 6. Misc ────────────────────────────────────────────────────────────

    @Test
    fun `defaults snapshot is sane`() {
        // The default policy is master-OFF so a fresh install never surprises.
        val v = gate(ProactivitySettings.DEFAULT).evaluate(speak(), null)
        assertTrue(v is ProactivityGate.Verdict.Suppress)
        assertEquals("master_disabled", (v as ProactivityGate.Verdict.Suppress).reason)
    }
}
