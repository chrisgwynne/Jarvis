package com.jarvis.assistant.core.decisions.triggers

import com.jarvis.assistant.core.context.AgentContext
import com.jarvis.assistant.core.decisions.Candidate
import com.jarvis.assistant.core.decisions.Trigger
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.proactive.ProactiveConfig
import com.jarvis.assistant.proactive.ProactiveEventType
import java.util.Calendar

class DailyAgendaTrigger(
    private val config: ProactiveConfig = ProactiveConfig(),
) : Trigger {
    override val id: String = "daily_agenda"
    override val actionClass: String = "CALENDAR"

    override fun match(ctx: AgentContext, recentEvents: List<Event>): Candidate? {
        val snapshot = ctx.proactive
        if (snapshot.meetingsTodayCount <= 0) return null
        val cal = Calendar.getInstance().apply { timeInMillis = snapshot.currentTimeMillis }
        if (cal.get(Calendar.HOUR_OF_DAY) != config.agendaHourStart) return null

        val yyyymmdd = "%04d%02d%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
        val firstTitle = snapshot.nextMeetingTitle?.takeIf { it.isNotBlank() }
        val count = snapshot.meetingsTodayCount
        val plural = if (count == 1) "one meeting" else "$count meetings"
        val spokenText = if (firstTitle != null) "$plural today. First up, $firstTitle."
        else "$plural today."

        return Candidate(
            triggerId = id,
            eventType = ProactiveEventType.DAILY_AGENDA,
            title = "Today's calendar",
            spokenText = spokenText,
            urgency = 0.45f,
            relevance = 0.60f,
            confidence = 1.0f,
            annoyanceCost = 0.25f,
            dedupeKey = "daily_agenda_$yyyymmdd",
            actionClass = actionClass,
            metadata = mapOf("meetingsTodayCount" to count.toString()),
        )
    }
}
