package com.jarvis.assistant.core.decisions.triggers

import com.jarvis.assistant.core.context.AgentContext
import com.jarvis.assistant.core.decisions.Candidate
import com.jarvis.assistant.core.decisions.Trigger
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.proactive.ProactiveEventType
import com.jarvis.assistant.voice.VoiceFeatureFlags

class UnreadNotificationTrigger : Trigger {
    override val id: String = "unread_notification"
    override val actionClass: String = "NOTIFICATION"

    companion object {
        /**
         * Urgency at which a notification reaches InterruptLevel.ACTIVE on
         * the default scoring config.  Messaging-app notifications use this
         * value (when the announce flag is on) so they become SpeakActions
         * instead of silent PassiveActions.
         */
        private const val MESSAGING_URGENCY: Float = 0.85f
        private const val DEFAULT_URGENCY:   Float = 0.55f
    }

    override fun match(ctx: AgentContext, recentEvents: List<Event>): Candidate? {
        val snapshot = ctx.proactive
        if (snapshot.unreadNotificationCount <= 0) return null
        if (snapshot.isJarvisSpeaking || snapshot.isJarvisListening) return null

        val count = snapshot.unreadNotificationCount
        val text = snapshot.lastNotificationText
        val app = snapshot.lastNotificationApp

        // Defensive: do not surface Home Assistant alerts via the proactive
        // speech path even if the upstream filters miss them.  Motion / camera /
        // doorbell events are visible in the notification shade; speaking them
        // out loud unprompted is exactly what the user does not want.
        if (app != null && com.jarvis.assistant.core.events.input
                .HomeAssistantNotificationClassifier
                .isHomeAssistantAlert(app, null, text)) {
            return null
        }

        // ── Messaging-app announce path ─────────────────────────────────────
        // When a messaging-class app posts a notification AND the feature
        // flag is on, elevate urgency so EventScorer maps the event to
        // InterruptLevel.ACTIVE → TtsProactiveDispatcher will speak it.
        // Also use a per-app dedupeKey instead of the global per-minute one
        // so two different chats arriving in the same minute both speak,
        // and use a friendlier display name ("WhatsApp" not "whatsapp").
        val announceMessaging = MessagingAppClassifier.isMessagingApp(app) &&
            VoiceFeatureFlags.isEnabled(
                VoiceFeatureFlags.Flag.MESSAGING_NOTIFICATION_ANNOUNCE_ENABLED
            )
        val displayName = MessagingAppClassifier.displayName(app)
            ?: app?.substringAfterLast('.')?.replaceFirstChar { it.titlecase() }

        val spokenText = when {
            announceMessaging && !text.isNullOrBlank() ->
                buildMessagingSpoken(displayName ?: "Message", text)
            count == 1 && text != null && displayName != null -> "$displayName: $text"
            count == 1 && displayName != null -> "Something from $displayName."
            count == 1 && text != null -> "New message — $text"
            count == 1 -> "You've got a new notification."
            displayName != null -> "$count new from $displayName."
            else -> "$count new notifications."
        }
        val titleLabel = if (count == 1) "New notification" else "$count new notifications"

        // Per-app dedupe key when announcing messaging — otherwise the
        // global per-minute bucket would collapse two senders into one
        // event and we'd only hear about the first.
        val dedupeKey = if (announceMessaging) {
            val perMinute = snapshot.currentTimeMillis / 60_000L
            "msg_announce_${app}_${(text ?: "").hashCode()}_$perMinute"
        } else {
            "unread_notification_${snapshot.currentTimeMillis / 60_000L}"
        }

        return Candidate(
            triggerId = id,
            eventType = ProactiveEventType.UNREAD_NOTIFICATION,
            title = titleLabel,
            spokenText = spokenText,
            urgency = if (announceMessaging) MESSAGING_URGENCY else DEFAULT_URGENCY,
            relevance = 0.70f,
            confidence = 1.0f,
            annoyanceCost = if (announceMessaging) 0.20f else 0.30f,
            dedupeKey = dedupeKey,
            actionClass = actionClass,
            metadata = buildMap {
                put("count", count.toString())
                if (app != null) put("app", app)
                if (text != null) put("text", text)
                put("announce_messaging", announceMessaging.toString())
            },
        )
    }

    /**
     * Format a messaging-app notification as a single short spoken line:
     *
     *   "WhatsApp from Wifey ❤️: are you coming"
     *
     * WhatsApp's notification text is usually `<sender>: <body>` for both
     * direct messages (when the title is the sender) and group chats.
     * If the text already starts with `<sender>:` we use it as the sender;
     * otherwise we speak `<app>: <text>` verbatim.
     *
     * Visible-for-test so the unit test can exercise the format directly.
     */
    internal fun buildMessagingSpoken(app: String, raw: String): String {
        val text = raw.trim()
        val firstColon = text.indexOf(':')
        return if (firstColon in 1..40) {
            val sender = text.substring(0, firstColon).trim()
            val body   = text.substring(firstColon + 1).trim()
            if (body.isNotBlank()) "$app from $sender: $body" else "$app from $sender."
        } else {
            "$app: $text"
        }
    }
}
