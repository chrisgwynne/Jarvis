package com.jarvis.assistant.proactive

/**
 * ProactiveConfig — all tuneable constants for the proactive engine.
 *
 * Centralising configuration here means each decision threshold, cooldown
 * window, and penalty weight can be adjusted without touching any logic.
 *
 * ## Policy layers
 *
 * The engine implements the Jarvis "observe everything → act rarely" policy
 * through four surfaced layers (a fifth, silent awareness, is the default —
 * the engine simply doesn't emit an action):
 *
 *   L0  Silent awareness        — no surfaced action; ≈80% of events.
 *   L1  Passive context         — no output, state adjusts (not modelled here;
 *                                 lives in ContextEngine / PromptAssembler).
 *   L2  Soft suggestion         — InterruptLevel.PASSIVE (quiet notification).
 *   L3  Contextual assistance   — InterruptLevel.ACTIVE when relevance is high
 *                                 and the moment is tied to a user action.
 *   L4  Active intervention     — InterruptLevel.ACTIVE with critical urgency
 *                                 (low battery, imminent alarm, urgent reminder).
 *
 * Ignore/accept adaptation scales cooldowns per dedupeKey: ignored suggestions
 * get progressively longer cooldowns ([ignoreEscalationFactor]); accepted ones
 * reset back to the baseline.
 *
 * Quiet hours suppress every event except [ProactiveEventType.LOW_BATTERY] and
 * imminent reminders (within [reminderUrgentMs]) when the current hour falls
 * in [quietHoursStartHour, quietHoursEndHour) — wrapping across midnight.
 *
 * @param pollingIntervalMs       How often the engine polls for new events (ms).
 * @param passiveThreshold        finalScore must exceed this to produce a PassiveAction.
 * @param activeThreshold         finalScore must exceed this to produce a SpeakAction.
 * @param cooldownLowBatteryMs    Minimum gap between consecutive LOW_BATTERY surfacings.
 * @param cooldownUpcomingReminderMs Minimum gap between consecutive UPCOMING_REMINDER surfacings.
 * @param cooldownMissedCallMs    Minimum gap between consecutive MISSED_CALL surfacings.
 * @param minGlobalGapMs          Minimum wall-clock gap between *any* two surfaced actions.
 * @param batteryLow              Battery level (%) at which the low-battery generator activates.
 * @param batteryVeryLow          Battery level (%) mapped to high urgency.
 * @param batteryCritical         Battery level (%) mapped to critical urgency.
 * @param reminderWindowMs        Look-ahead window for upcoming reminders (ms).
 * @param reminderHighWindowMs    Window for high-urgency reminder scoring (ms).
 * @param reminderUrgentMs        Window for critical/urgent reminder scoring (ms).
 * @param repetitionPenalty       Score penalty applied when an event is within its cooldown window.
 * @param speakingPenalty         Score penalty applied when Jarvis is currently speaking.
 * @param recentInteractionPenalty Score penalty applied when the user just interacted.
 * @param recentInteractionWindowMs Window within which the user is considered to have just interacted.
 * @param eventStalenessMs        Events older than this are discarded by DecisionEngine.
 * @param quietHoursStartHour     Optional start of nightly quiet window (0–23); null disables.
 * @param quietHoursEndHour       Optional end of nightly quiet window (0–23); wraps past midnight.
 * @param ignoreEscalationFactor  Each past ignore multiplies the effective cooldown by
 *                                (1 + factor × ignoreCount).  0 disables adaptation.
 * @param ignoreCheckDelayMs      After a surfacing, if no user interaction occurs within
 *                                this window the action is counted as ignored.
 */
data class ProactiveConfig(
    val pollingIntervalMs: Long           = 10_000L,
    val passiveThreshold: Float           = 0.55f,
    val activeThreshold: Float            = 0.80f,
    val cooldownLowBatteryMs: Long        = 10 * 60 * 1000L,
    val cooldownUpcomingReminderMs: Long  = 5  * 60 * 1000L,
    val cooldownMissedCallMs: Long        = 15 * 60 * 1000L,
    val minGlobalGapMs: Long              = 60_000L,
    val batteryLow: Int                   = 15,
    val batteryVeryLow: Int               = 10,
    val batteryCritical: Int              = 5,
    val reminderWindowMs: Long            = 10 * 60 * 1000L,
    val reminderHighWindowMs: Long        = 5  * 60 * 1000L,
    val reminderUrgentMs: Long            = 60_000L,
    val repetitionPenalty: Float          = 0.30f,
    val speakingPenalty: Float            = 0.20f,
    val recentInteractionPenalty: Float   = 0.25f,
    val recentInteractionWindowMs: Long   = 30_000L,
    val eventStalenessMs: Long            = 30_000L,
    val quietHoursStartHour: Int?         = null,
    val quietHoursEndHour: Int?           = null,
    val ignoreEscalationFactor: Float     = 1.0f,
    val ignoreCheckDelayMs: Long          = 90_000L
)
