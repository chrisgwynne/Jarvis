package com.jarvis.assistant.core.decisions.triggers

import com.jarvis.assistant.core.context.AgentContext
import com.jarvis.assistant.core.decisions.Candidate
import com.jarvis.assistant.core.decisions.Trigger
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.proactive.ProactiveEventType

class UnreadNotificationTrigger : Trigger {
    override val id: String = "unread_notification"
    override val actionClass: String = "NOTIFICATION"

    override fun match(ctx: AgentContext, recentEvents: List<Event>): Candidate? {
        val snapshot = ctx.proactive
        if (snapshot.unreadNotificationCount <= 0) return null
        if (snapshot.isJarvisSpeaking || snapshot.isJarvisListening) return null

        val count = snapshot.unreadNotificationCount
        val text = snapshot.lastNotificationText
        val app = snapshot.lastNotificationApp
        val appLabel = app?.substringAfterLast('.')?.replaceFirstChar { it.titlecase() }
        val spokenText = when {
            count == 1 && text != null && appLabel != null -> "$appLabel: $text"
            count == 1 && appLabel != null -> "Something from $appLabel."
            count == 1 && text != null -> "New message — $text"
            count == 1 -> "You've got a new notification."
            appLabel != null -> "$count new from $appLabel."
            else -> "$count new notifications."
        }
        val titleLabel = if (count == 1) "New notification" else "$count new notifications"

        return Candidate(
            triggerId = id,
            eventType = ProactiveEventType.UNREAD_NOTIFICATION,
            title = titleLabel,
            spokenText = spokenText,
            urgency = 0.55f,
            relevance = 0.70f,
            confidence = 1.0f,
            annoyanceCost = 0.30f,
            dedupeKey = "unread_notification_${snapshot.currentTimeMillis / 60_000L}",
            actionClass = actionClass,
            metadata = buildMap {
                put("count", count.toString())
                if (app != null) put("app", app)
                if (text != null) put("text", text)
            },
        )
    }
}
