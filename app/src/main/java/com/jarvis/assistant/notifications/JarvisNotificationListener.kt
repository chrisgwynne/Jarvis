package com.jarvis.assistant.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.core.events.EventBus
import com.jarvis.assistant.core.events.EventKind
import java.util.LinkedList

/**
 * JarvisNotificationListener — a NotificationListenerService that maintains a
 * rolling ring buffer of recent notifications for use by Jarvis tools.
 *
 * SETUP REQUIRED:
 *   The user must grant Notification Access in:
 *     Settings → Apps → Special app access → Notification access → Jarvis
 *
 * The service is started and bound by the Android OS automatically once
 * access is granted — do NOT start it manually via startService().
 *
 * ARCHITECTURE:
 *   • Holds a static ring buffer of up to [MAX_ENTRIES] NotificationEntry objects.
 *   • ReadNotificationsTool / NotificationSummaryTool query the buffer via
 *     the companion object helpers.
 *   • Entries are keyed by StatusBarNotification.key for O(n) removal.
 *   • Each entry is enriched with sender, appName, isMessaging so tools can
 *     filter by contact name without parsing raw title strings.
 *   • RecentMessageContext is updated whenever a messaging notification arrives,
 *     enabling conversational "reply yes" follow-ups.
 */
class JarvisNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG         = "JarvisNotificationListener"
        private const val MAX_ENTRIES = 30
        private const val OWN_PACKAGE = "com.jarvis.assistant"

        // Thread-safe ring buffer shared across the process
        private val lock   = Any()
        private val buffer = LinkedList<NotificationEntry>()

        // ── Public query helpers ──────────────────────────────────────────────

        /** Returns a snapshot copy of the buffer (newest first). */
        fun getRecent(): List<NotificationEntry> = synchronized(lock) {
            buffer.toList().asReversed()
        }

        /**
         * Returns all buffered notifications from the given [packageName],
         * newest first.
         */
        fun getFromApp(packageName: String): List<NotificationEntry> =
            getRecent().filter { it.packageName == packageName }

        /**
         * Returns messaging notifications only (WhatsApp, SMS, Telegram, etc.),
         * newest first.
         */
        fun getRecentMessages(): List<NotificationEntry> =
            getRecent().filter { it.isMessaging }

        /**
         * Returns messaging notifications from a specific sender (case-insensitive
         * partial match on [NotificationEntry.sender] or title), newest first.
         */
        fun getMessagesFromSender(name: String): List<NotificationEntry> =
            getRecentMessages().filter {
                it.sender.contains(name, ignoreCase = true) ||
                it.title.contains(name, ignoreCase = true)
            }

        /**
         * Returns true if this app has been granted notification listener access.
         */
        fun isGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val ours = ComponentName(context, JarvisNotificationListener::class.java)
                .flattenToString()
            return flat.split(":").any { entry ->
                try {
                    ComponentName.unflattenFromString(entry)?.let { cn ->
                        cn.packageName == ours.substringBefore("/")
                    } ?: false
                } catch (_: Exception) { false }
            }
        }

        // ── Incoming call notification actions ────────────────────────────────

        private const val CALL_ACTIONS_TTL_MS = 30_000L
        private val callActionsLock = Any()
        @Volatile private var answerIntent:  PendingIntent? = null
        @Volatile private var declineIntent: PendingIntent? = null
        @Volatile private var callActionsTs: Long = 0L

        fun pollAnswerIntent(): PendingIntent? = synchronized(callActionsLock) {
            if (System.currentTimeMillis() - callActionsTs > CALL_ACTIONS_TTL_MS) answerIntent = null
            answerIntent
        }

        fun pollDeclineIntent(): PendingIntent? = synchronized(callActionsLock) {
            if (System.currentTimeMillis() - callActionsTs > CALL_ACTIONS_TTL_MS) declineIntent = null
            declineIntent
        }

        fun clearCallActions() = synchronized(callActionsLock) {
            answerIntent  = null
            declineIntent = null
        }

        private fun storeCallActions(sbn: StatusBarNotification) {
            val actions = sbn.notification?.actions?.takeIf { it.isNotEmpty() } ?: return
            var ans: PendingIntent? = null
            var dec: PendingIntent? = null
            for (action in actions) {
                val label = action.title?.toString()?.lowercase() ?: continue
                when {
                    ans == null && (label.contains("answer") || label.contains("accept")) ->
                        ans = action.actionIntent
                    dec == null && (label.contains("decline") || label.contains("reject") ||
                                    label.contains("dismiss") || label.contains("hang")) ->
                        dec = action.actionIntent
                }
            }
            if (ans == null && actions.isNotEmpty()) ans = actions.first().actionIntent
            if (dec == null && actions.size >= 2)    dec = actions.last().actionIntent
            synchronized(callActionsLock) {
                answerIntent  = ans
                declineIntent = dec
                callActionsTs = System.currentTimeMillis()
            }
            Log.d(TAG, "Stored call actions from ${sbn.packageName}: ${actions.size} actions, " +
                "answer=${ans != null} decline=${dec != null}")
        }

        // ── Incoming caller name cache ────────────────────────────────────────

        private val callerNameLock = Any()
        @Volatile private var cachedCallerName:   String? = null
        @Volatile private var cachedCallerSource: String? = null
        @Volatile private var callerNameTs: Long = 0L
        private const val CALLER_NAME_TTL_MS = 30_000L

        fun putCallerName(name: String, sourcePackage: String? = null) =
            synchronized(callerNameLock) {
                cachedCallerName   = name
                cachedCallerSource = sourcePackage
                callerNameTs       = System.currentTimeMillis()
            }

        fun peekCallerName(): String? = synchronized(callerNameLock) {
            if (System.currentTimeMillis() - callerNameTs > CALLER_NAME_TTL_MS) {
                cachedCallerName   = null
                cachedCallerSource = null
            }
            cachedCallerName
        }

        fun peekCallerSource(): String? = synchronized(callerNameLock) {
            if (System.currentTimeMillis() - callerNameTs > CALLER_NAME_TTL_MS) {
                cachedCallerName   = null
                cachedCallerSource = null
            }
            cachedCallerSource
        }

        fun pollCallerName(): String? = synchronized(callerNameLock) {
            val v = peekCallerName()
            cachedCallerName   = null
            cachedCallerSource = null
            return v
        }

        fun clearCallerName() = synchronized(callerNameLock) {
            cachedCallerName   = null
            cachedCallerSource = null
        }

        // ── Internal helpers ──────────────────────────────────────────────────

        private fun addEntry(entry: NotificationEntry) = synchronized(lock) {
            buffer.removeAll { it.sbnKey == entry.sbnKey }
            buffer.addLast(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        }

        private fun removeEntry(sbnKey: String) = synchronized(lock) {
            buffer.removeAll { it.sbnKey == sbnKey }
        }

        // ── Live-service handle ───────────────────────────────────────────────

        @Volatile private var instance: JarvisNotificationListener? = null

        fun isConnected(): Boolean = instance != null

        fun clearAll(): Int {
            val svc   = instance ?: return -1
            val count = synchronized(lock) { buffer.size }
            return try {
                svc.cancelAllNotifications()
                synchronized(lock) { buffer.clear() }
                count
            } catch (e: Exception) {
                Log.w(TAG, "cancelAllNotifications threw: ${e.message}")
                -1
            }
        }

        fun clearFromApp(packageName: String): Int {
            val svc  = instance ?: return -1
            val keys = synchronized(lock) {
                buffer.filter { it.packageName == packageName }.map { it.sbnKey }
            }
            if (keys.isEmpty()) return 0
            var cleared = 0
            for (key in keys) {
                try { svc.cancelNotification(key); cleared++ }
                catch (e: Exception) { Log.w(TAG, "cancelNotification($key) threw: ${e.message}") }
            }
            synchronized(lock) { buffer.removeAll { keys.contains(it.sbnKey) } }
            return cleared
        }

        // ── Entry builder ─────────────────────────────────────────────────────

        private fun buildEntry(
            packageName: String,
            title: String,
            text: String,
            postedAt: Long,
            sbnKey: String,
            replyPendingIntent: PendingIntent?,
            replyRemoteInputs: List<android.app.RemoteInput>,
        ): NotificationEntry {
            val cap        = MessagingAppCapabilityRegistry.forPackage(packageName)
            val isMsg      = MessagingAppCapabilityRegistry.isMessagingApp(packageName)
            // For messaging apps the notification title is the contact/group name
            val sender     = if (isMsg) title else ""
            return NotificationEntry(
                packageName        = packageName,
                title              = title,
                text               = text,
                postedAt           = postedAt,
                sbnKey             = sbnKey,
                replyPendingIntent = replyPendingIntent,
                replyRemoteInputs  = replyRemoteInputs,
                sender             = sender,
                appName            = cap.displayName,
                isMessaging        = isMsg,
            )
        }
    }

    // ── NotificationListenerService callbacks ─────────────────────────────────

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d(TAG, "Listener connected — backfilling active notifications")
        try {
            val active = getActiveNotifications() ?: emptyArray()
            for (sbn in active) {
                if (sbn.packageName == OWN_PACKAGE) continue
                val extras = sbn.notification?.extras ?: continue
                val title  = extras.getCharSequence("android.title")?.toString()?.trim()
                val text   = (extras.getCharSequence("android.bigText")
                    ?: extras.getCharSequence("android.text"))?.toString()?.trim()
                if (title.isNullOrEmpty() && text.isNullOrEmpty()) continue
                val replyAction = sbn.notification?.actions?.firstOrNull { a ->
                    a.remoteInputs?.isNotEmpty() == true && a.actionIntent != null
                }
                val entry = buildEntry(
                    packageName        = sbn.packageName,
                    title              = title ?: "",
                    text               = text  ?: "",
                    postedAt           = sbn.postTime,
                    sbnKey             = sbn.key,
                    replyPendingIntent = replyAction?.actionIntent,
                    replyRemoteInputs  = replyAction?.remoteInputs?.toList() ?: emptyList(),
                )
                addEntry(entry)
                if (entry.isMessaging) RecentMessageContext.update(entry)
            }
            Log.d(TAG, "Backfill complete — ${synchronized(lock) { buffer.size }} entries in buffer")
        } catch (e: Exception) {
            Log.w(TAG, "Backfill failed: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        instance = null
        Log.d(TAG, "Listener disconnected")
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == OWN_PACKAGE) return

        val notif = sbn.notification
        val isCallNotif = notif != null &&
            (notif.fullScreenIntent != null || notif.category == Notification.CATEGORY_CALL)
        if (isCallNotif) {
            storeCallActions(sbn)
            val title = notif?.extras?.getCharSequence("android.title")?.toString()?.trim()
            if (!title.isNullOrBlank()) {
                putCallerName(title, sbn.packageName)
                Log.d(TAG, "Cached caller name from notification: \"$title\" (${sbn.packageName})")
            }
        }

        val extras = sbn.notification?.extras ?: return
        val title  = extras.getCharSequence("android.title")?.toString()?.trim()
        val text   = (extras.getCharSequence("android.bigText")
            ?: extras.getCharSequence("android.text"))?.toString()?.trim()

        if (title.isNullOrEmpty() && text.isNullOrEmpty()) return

        val isHaAlert = com.jarvis.assistant.core.events.input
            .HomeAssistantNotificationClassifier
            .isHomeAssistantAlert(sbn.packageName, title, text)

        val replyAction = sbn.notification?.actions?.firstOrNull { action ->
            action.remoteInputs?.isNotEmpty() == true && action.actionIntent != null
        }
        val replyInputs = replyAction?.remoteInputs?.toList() ?: emptyList()

        if (!isHaAlert) {
            val entry = buildEntry(
                packageName        = sbn.packageName,
                title              = title ?: "",
                text               = text  ?: "",
                postedAt           = sbn.postTime,
                sbnKey             = sbn.key,
                replyPendingIntent = replyAction?.actionIntent,
                replyRemoteInputs  = replyInputs,
            )
            addEntry(entry)
            // Update conversational message context so "reply yes" works immediately
            if (entry.isMessaging) {
                RecentMessageContext.update(entry)
                Log.d(TAG, "[MSG_CONTEXT_UPDATED] sender=\"${entry.sender}\" app=${entry.appName}")
            }
            Log.v(TAG, "Buffered notification from ${sbn.packageName}: $title")
        } else {
            Log.d(TAG, "[HA_ALERT_SKIPPED_BUFFER] pkg=${sbn.packageName} title=\"$title\"")
        }

        EventBus.publish(
            kind    = EventKind.NOTIFICATION_POSTED,
            source  = "JarvisNotificationListener",
            payload = buildMap {
                put("app_package",  sbn.packageName)
                put("is_messaging", MessagingAppCapabilityRegistry.isMessagingApp(sbn.packageName).toString())
                if (!title.isNullOrEmpty()) put("title", title)
                if (!text.isNullOrEmpty())  put("text",  text)
                put("is_call",     isCallNotif.toString())
                put("is_ha_alert", isHaAlert.toString())
            },
            sensitivity = Event.Sensitivity.PERSONAL,
            dedupeKey   = sbn.key,
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        removeEntry(sbn.key)
        Log.v(TAG, "Removed notification from buffer: key=${sbn.key}")
        EventBus.publish(
            kind        = EventKind.NOTIFICATION_REMOVED,
            source      = "JarvisNotificationListener",
            payload     = mapOf("app_package" to sbn.packageName),
            sensitivity = Event.Sensitivity.PUBLIC,
            dedupeKey   = sbn.key,
        )
    }
}

/**
 * A single notification entry stored in the ring buffer.
 *
 * Fields with defaults are safe for existing construction sites that pre-date
 * the messaging enrichment pass.
 *
 * @param sender      The contact/group name for messaging notifications.
 *                    Empty for non-messaging apps; callers should fall back to [title].
 * @param appName     Human-readable app name ("WhatsApp", "Messages", "Slack").
 * @param isMessaging True for WhatsApp, SMS, Telegram, Signal, Slack, etc.
 */
data class NotificationEntry(
    val packageName:        String,
    val title:              String,
    val text:               String,
    val postedAt:           Long,
    val sbnKey:             String,
    val replyPendingIntent: android.app.PendingIntent? = null,
    val replyRemoteInputs:  List<android.app.RemoteInput> = emptyList(),
    val sender:             String = "",
    val appName:            String = "",
    val isMessaging:        Boolean = false,
) {
    /** True when this notification has a reply action that can be triggered programmatically. */
    val canReply: Boolean get() = replyPendingIntent != null && replyRemoteInputs.isNotEmpty()

    /** Best available display name for the sender (sender if set, otherwise title). */
    val displaySender: String get() = sender.ifBlank { title }
}
