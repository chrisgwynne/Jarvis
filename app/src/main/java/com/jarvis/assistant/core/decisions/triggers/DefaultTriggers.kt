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
    fun build(
        config: ProactiveConfig = ProactiveConfig(),
        knownSsidStore: com.jarvis.assistant.core.learning.KnownSsidStore? = null,
        routineSynthesizer: com.jarvis.assistant.core.routines.RoutineSynthesizer? = null,
    ): List<Trigger> = buildList {
        add(LowBatteryTrigger(config))
        add(UpcomingReminderTrigger(config))
        add(MissedCallTrigger())
        add(UnreadNotificationTrigger())
        add(BehavioralLearningTrigger())
        add(MeetingStartingSoonTrigger(config))
        add(UpcomingMeetingTrigger(config))
        add(DailyAgendaTrigger(config))
        add(LocationTransitionTrigger(config))
        add(LowBatteryBeforeTravelTrigger(config))
        add(HomeAssistantMotionAwayTrigger())
        if (knownSsidStore != null) add(UnfamiliarSsidTrigger(knownSsidStore))
        if (routineSynthesizer != null) add(RoutineProposalTrigger(routineSynthesizer))
    }

    fun engine(
        config: ProactiveConfig = ProactiveConfig(),
        knownSsidStore: com.jarvis.assistant.core.learning.KnownSsidStore? = null,
        routineSynthesizer: com.jarvis.assistant.core.routines.RoutineSynthesizer? = null,
    ): TriggerEngine = TriggerEngine(build(config, knownSsidStore, routineSynthesizer))
}
