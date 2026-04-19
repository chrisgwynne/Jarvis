package com.jarvis.assistant.core.decisions.triggers

import com.jarvis.assistant.core.decisions.Trigger
import com.jarvis.assistant.core.decisions.TriggerEngine
import com.jarvis.assistant.proactive.ProactiveConfig

/**
 * DefaultTriggers — the canonical list of shipped triggers, wired from
 * [ProactiveConfig]. Produces the same signal set the legacy
 * [com.jarvis.assistant.proactive.EventGenerator] produced; both paths now
 * route through this same list.
 *
 * Add new triggers by appending here. Order does not matter — scoring
 * happens downstream in the policy engine.
 */
object DefaultTriggers {
    fun build(config: ProactiveConfig = ProactiveConfig()): List<Trigger> = listOf(
        LowBatteryTrigger(config),
        UpcomingReminderTrigger(config),
        MissedCallTrigger(),
        UnreadNotificationTrigger(),
        BehavioralLearningTrigger(),
        MeetingStartingSoonTrigger(config),
        UpcomingMeetingTrigger(config),
        DailyAgendaTrigger(config),
        LocationTransitionTrigger(config),
        LowBatteryBeforeTravelTrigger(config),
    )

    fun engine(config: ProactiveConfig = ProactiveConfig()): TriggerEngine =
        TriggerEngine(build(config))
}
