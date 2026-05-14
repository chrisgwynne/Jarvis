package com.jarvis.assistant.proactive

import android.util.Log
import com.jarvis.assistant.core.events.input.HomeAssistantNotificationClassifier
import com.jarvis.assistant.notifications.JarvisNotificationListener

/**
 * AppNotificationSource — wraps [JarvisNotificationListener]'s static ring buffer
 * to expose unread notification state to the [ProactiveEngine].
 *
 * "Unread" means: arrived after [lastAcknowledgedAtMs] (set by [acknowledge]).
 * The engine calls [acknowledge] after surfacing a notification event so the
 * same notifications are not announced repeatedly.
 *
 * Notifications from Jarvis itself (package=com.jarvis.assistant) are always excluded.
 */
class AppNotificationSource : NotificationContextSource {

    companion object {
        private const val TAG = "AppNotificationSource"
        private const val OWN_PKG = "com.jarvis.assistant"
        /** Minimum idle time (ms) before proactively announcing notifications. */
        const val MIN_IDLE_MS = 30_000L
    }

    @Volatile private var lastAcknowledgedAtMs: Long = System.currentTimeMillis()

    private fun unread() = JarvisNotificationListener.getRecent()
        .filter { entry ->
            entry.packageName != OWN_PKG &&
                entry.postedAt > lastAcknowledgedAtMs &&
                // Defensive: the listener already skips HA alerts at ingestion,
                // but if a HA notification ever slipped through (e.g. a new
                // companion package variant), we still refuse to surface it
                // to the proactive engine here.
                !HomeAssistantNotificationClassifier.isHomeAssistantAlert(
                    entry.packageName, entry.title, entry.text
                )
        }

    override fun getUnreadCount(): Int = unread().size

    override fun getLastNotificationText(): String? {
        val n = unread().firstOrNull() ?: return null
        return listOfNotNull(
            n.title.takeIf { it.isNotBlank() },
            n.text.takeIf { it.isNotBlank() }
        ).joinToString(": ").takeIf { it.isNotBlank() }
    }

    override fun getLastNotificationApp(): String? = unread().firstOrNull()?.packageName

    override fun acknowledge() {
        lastAcknowledgedAtMs = System.currentTimeMillis()
        Log.d(TAG, "Acknowledged notifications at $lastAcknowledgedAtMs")
    }
}
