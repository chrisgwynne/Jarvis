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
 *   • ReadNotificationsTool queries the buffer via the companion object helpers.
 *   • Entries are keyed by StatusBarNotification.key for O(n) removal.
 */
class JarvisNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG        = "JarvisNotificationListener"
        private const val MAX_ENTRIES = 20
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
         * Returns true if this app has been granted notification listener access.
         *
         * Checks the colon-delimited list in Settings.Secure
         * "enabled_notification_listeners" for our ComponentName.
         */
        fun isGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false

            val ours = ComponentName(context, JarvisNotificationListener::class.java)
                .flattenToString()

            return flat.split(":").any { entry ->
                // Entry format is "package/class" or "package/.ShortClass"
                try {
                    ComponentName.unflattenFromString(entry)?.let { cn ->
                        cn.packageName == ours.substringBefore("/")
                    } ?: false
                } catch (_: Exception) {
                    false
                }
            }
        }

        // ── Incoming call notification actions ────────────────────────────────
        //
        // On Android 10+ TelecomManager.acceptRingingCall() is restricted to
        // system apps.  The correct approach for assistants is to fire the
        // "Answer" PendingIntent embedded in the incoming call notification —
        // the same technique used by Android Auto and Wear OS.
        //
        // JarvisNotificationListener captures those intents here; the executor
        // retrieves them via pollAnswerIntent() / pollDeclineIntent().

        private const val CALL_ACTIONS_TTL_MS = 30_000L
        private val callActionsLock = Any()
        @Volatile private var answerIntent:  PendingIntent? = null
        @Volatile private var declineIntent: PendingIntent? = null
        @Volatile private var callActionsTs: Long = 0L

        /** Returns the captured Answer PendingIntent if still within TTL. */
        fun pollAnswerIntent(): PendingIntent? = synchronized(callActionsLock) {
            if (System.currentTimeMillis() - callActionsTs > CALL_ACTIONS_TTL_MS) {
                answerIntent = null
            }
            answerIntent
        }

        /** Returns the captured Decline PendingIntent if still within TTL. */
        fun pollDeclineIntent(): PendingIntent? = synchronized(callActionsLock) {
            if (System.currentTimeMillis() - callActionsTs > CALL_ACTIONS_TTL_MS) {
                declineIntent = null
            }
            declineIntent
        }

        /** Clears stored call actions after the call is handled. */
        fun clearCallActions() = synchronized(callActionsLock) {
            answerIntent  = null
            declineIntent = null
        }

        private fun storeCallActions(sbn: StatusBarNotification) {
            val actions = sbn.notification?.actions?.takeIf { it.isNotEmpty() } ?: return
            var ans: PendingIntent? = null
            var dec: PendingIntent? = null

            // Pass 1: label-based matching (works when actions are in English or common labels)
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

            // Pass 2: position-based fallback for localized labels
            // Call notifications almost always order: first=answer, last=decline
            if (ans == null && actions.isNotEmpty()) ans  = actions.first().actionIntent
            if (dec == null && actions.size >= 2)    dec  = actions.last().actionIntent

            synchronized(callActionsLock) {
                answerIntent  = ans
                declineIntent = dec
                callActionsTs = System.currentTimeMillis()
            }
            Log.d(TAG, "Stored call actions from ${sbn.packageName}: ${actions.size} actions, " +
                       "answer=${ans != null} decline=${dec != null}")
        }

        // ── Incoming caller name cache (from notification title) ──────────────
        //
        // Call notifications (WhatsApp, system dialer) put the caller's name
        // as the notification title.  We cache it here so ContactsPhoneLookupResolver
        // can use it when the phone number is not available (Android 12+).

        private val callerNameLock = Any()
        @Volatile private var cachedCallerName: String? = null
        @Volatile private var cachedCallerSource: String? = null
        @Volatile private var callerNameTs: Long = 0L
        private const val CALLER_NAME_TTL_MS = 30_000L

        fun putCallerName(name: String, sourcePackage: String? = null) =
            synchronized(callerNameLock) {
                cachedCallerName   = name
                cachedCallerSource = sourcePackage
                callerNameTs       = System.currentTimeMillis()
            }

        /**
         * Peek the cached caller name without clearing it.
         *
         * The resolver may call this several times while waiting for the call
         * notification to arrive, so clearing on first read loses the name.
         * Callers that want read-and-clear semantics should use [pollCallerName].
         */
        fun peekCallerName(): String? = synchronized(callerNameLock) {
            if (System.currentTimeMillis() - callerNameTs > CALLER_NAME_TTL_MS) {
                cachedCallerName   = null
                cachedCallerSource = null
            }
            cachedCallerName
        }

        /** The package that posted the most recent call notification (e.g. com.whatsapp). */
        fun peekCallerSource(): String? = synchronized(callerNameLock) {
            if (System.currentTimeMillis() - callerNameTs > CALLER_NAME_TTL_MS) {
                cachedCallerName   = null
                cachedCallerSource = null
            }
            cachedCallerSource
        }

        /** Read and clear the cached caller name. */
        fun pollCallerName(): String? = synchronized(callerNameLock) {
            val v = peekCallerName()
            cachedCallerName   = null
            cachedCallerSource = null
            return v
        }

        /** Explicitly drop the cached name — call on CallEnded. */
        fun clearCallerName() = synchronized(callerNameLock) {
            cachedCallerName   = null
            cachedCallerSource = null
        }

        // ── Internal helpers ──────────────────────────────────────────────────

        private fun addEntry(entry: NotificationEntry) = synchronized(lock) {
            // Deduplicate by key — replace if already present
            buffer.removeAll { it.sbnKey == entry.sbnKey }
            buffer.addLast(entry)
            // Trim to ring buffer size
            while (buffer.size > MAX_ENTRIES) {
                buffer.removeFirst()
            }
        }

        private fun removeEntry(sbnKey: String) = synchronized(lock) {
            buffer.removeAll { it.sbnKey == sbnKey }
        }

        // ── Live-service handle + cancellation helpers ────────────────────────
        //
        // cancelAllNotifications / cancelNotification are instance methods on
        // NotificationListenerService — they can't be called without a live
        // bound service.  Expose a @Volatile singleton (same pattern as
        // JarvisAccessibilityService) so ClearNotificationsTool can reach the
        // running service from outside its process.  Cleared on unbind/destroy
        // so revoking Notification Access in Settings makes isConnected()
        // return false and the tool fails cleanly.

        @Volatile private var instance: JarvisNotificationListener? = null

        /** True if the listener is currently bound to the OS. */
        fun isConnected(): Boolean = instance != null

        /**
         * Cancel every clearable notification the listener can see.
         *
         * Returns the number of notifications buffered by us at the moment of
         * the call — used only as a UX hint ("5 notifications cleared").
         * Android filters out non-clearable entries (foreground services,
         * persistent low-importance) automatically at the framework layer.
         */
        fun clearAll(): Int {
            val svc = instance ?: return -1
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

        /**
         * Cancel every buffered notification from [packageName].
         *
         * Returns the number of notifications Jarvis actually dispatched
         * cancels for; -1 when the listener isn't connected.
         */
        fun clearFromApp(packageName: String): Int {
            val svc = instance ?: return -1
            val keys = synchronized(lock) {
                buffer.filter { it.packageName == packageName }.map { it.sbnKey }
            }
            if (keys.isEmpty()) return 0
            var cleared = 0
            for (key in keys) {
                try {
                    svc.cancelNotification(key)
                    cleared++
                } catch (e: Exception) {
                    Log.w(TAG, "cancelNotification($key) threw: ${e.message}")
                }
            }
            synchronized(lock) { buffer.removeAll { keys.contains(it.sbnKey) } }
            return cleared
        }
    }

    // ── NotificationListenerService callbacks ─────────────────────────────────

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d(TAG, "Listener connected — backfilling active notifications")
        // Seed the buffer with notifications already on the device.
        // Without this, asking for notifications immediately after granting access
        // always returns empty because onNotificationPosted only fires for new arrivals.
        try {
            val active = getActiveNotifications() ?: emptyArray()
            for (sbn in active) {
                if (sbn.packageName == OWN_PACKAGE) continue
                val extras = sbn.notification?.extras ?: continue
                val title  = extras.getCharSequence("android.title")?.toString()?.trim()
                val text   = (
                    extras.getCharSequence("android.bigText")
                        ?: extras.getCharSequence("android.text")
                )?.toString()?.trim()
                if (title.isNullOrEmpty() && text.isNullOrEmpty()) continue
                val replyAction = sbn.notification?.actions?.firstOrNull { a ->
                    a.remoteInputs?.isNotEmpty() == true && a.actionIntent != null
                }
                addEntry(NotificationEntry(
                    packageName        = sbn.packageName,
                    title              = title ?: "",
                    text               = text  ?: "",
                    postedAt           = sbn.postTime,
                    sbnKey             = sbn.key,
                    replyPendingIntent = replyAction?.actionIntent,
                    replyRemoteInputs  = replyAction?.remoteInputs?.toList() ?: emptyList(),
                ))
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
        // Belt-and-braces: onListenerDisconnected isn't always called.
        instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Ignore our own notifications to prevent feedback loops
        if (sbn.packageName == OWN_PACKAGE) return

        // Detect incoming call notifications.
        // Use fullScreenIntent as the signal — ALL incoming call notifications (WhatsApp,
        // system dialer, Teams, etc.) set this to pop up over the lock screen.
        // CATEGORY_CALL is an optional secondary signal; not all apps set it.
        val notif = sbn.notification
        val isCallNotif = notif != null &&
            (notif.fullScreenIntent != null || notif.category == Notification.CATEGORY_CALL)

        if (isCallNotif) {
            storeCallActions(sbn)
            // Cache the notification title as the caller name — WhatsApp and the system
            // dialer put the contact's name here, which lets us announce the caller even
            // on Android 12+ where TelephonyCallback omits the phone number.
            val title = notif?.extras?.getCharSequence("android.title")?.toString()?.trim()
            if (!title.isNullOrBlank()) {
                putCallerName(title, sbn.packageName)
                Log.d(TAG, "Cached caller name from notification: \"$title\" (${sbn.packageName})")
            }
        }

        val extras = sbn.notification?.extras ?: return
        val title  = extras.getCharSequence("android.title")?.toString()?.trim()
        val text   = (
            extras.getCharSequence("android.bigText")
                ?: extras.getCharSequence("android.text")
        )?.toString()?.trim()

        // Only buffer entries with at least a title or text body
        if (title.isNullOrEmpty() && text.isNullOrEmpty()) return

        // ── Home Assistant alert filter ──────────────────────────────────────
        // Motion / camera / doorbell notifications from the HA companion app
        // must NEVER be spoken by the proactive engine.  We keep them visible
        // in the Android notification shade (the user can still see them) but
        // skip the ring buffer that feeds the proactive "unread" path and tag
        // the EventBus payload so any downstream consumer can recognise them.
        val isHaAlert = com.jarvis.assistant.core.events.input
            .HomeAssistantNotificationClassifier
            .isHomeAssistantAlert(sbn.packageName, title, text)

        // Extract a reply action if present (WhatsApp, Messages, etc.)
        val replyAction = sbn.notification?.actions?.firstOrNull { action ->
            action.remoteInputs?.isNotEmpty() == true && action.actionIntent != null
        }
        val replyInputs = replyAction?.remoteInputs?.toList() ?: emptyList()

        if (!isHaAlert) {
            val entry = NotificationEntry(
                packageName        = sbn.packageName,
                title              = title ?: "",
                text               = text  ?: "",
                postedAt           = sbn.postTime,
                sbnKey             = sbn.key,
                replyPendingIntent = replyAction?.actionIntent,
                replyRemoteInputs  = replyInputs,
            )
            addEntry(entry)
            Log.v(TAG, "Buffered notification from ${sbn.packageName}: $title")
        } else {
            Log.d(TAG, "[HA_ALERT_SKIPPED_BUFFER] pkg=${sbn.packageName} title=\"$title\" " +
                "text=\"$text\" — not eligible for proactive speech")
        }

        EventBus.publish(
            kind = EventKind.NOTIFICATION_POSTED,
            source = "JarvisNotificationListener",
            payload = buildMap {
                put("app_package", sbn.packageName)
                if (!title.isNullOrEmpty()) put("title", title)
                if (!text.isNullOrEmpty()) put("text", text)
                put("is_call", isCallNotif.toString())
                // Downstream consumers (proactive triggers, dispatcher) must check
                // this flag and refuse to convert the event into a SpeakAction.
                put("is_ha_alert", isHaAlert.toString())
            },
            sensitivity = Event.Sensitivity.PERSONAL,
            dedupeKey = sbn.key,
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        removeEntry(sbn.key)
        Log.v(TAG, "Removed notification from buffer: key=${sbn.key}")
        EventBus.publish(
            kind = EventKind.NOTIFICATION_REMOVED,
            source = "JarvisNotificationListener",
            payload = mapOf("app_package" to sbn.packageName),
            sensitivity = Event.Sensitivity.PUBLIC,
            dedupeKey = sbn.key,
        )
    }
}

/**
 * A single notification entry stored in the ring buffer.
 *
 * @param packageName        App package that posted the notification.
 * @param title              Notification title (may be empty).
 * @param text               Notification body text (may be empty).
 * @param postedAt           [System.currentTimeMillis] when the notification was posted.
 * @param sbnKey             Unique key from [StatusBarNotification.getKey] — used for removal.
 * @param replyPendingIntent PendingIntent to fire when sending a voice reply (null if not replyable).
 * @param replyRemoteInputs  RemoteInput array from the reply action — needed by addResultsToIntent.
 */
data class NotificationEntry(
    val packageName:        String,
    val title:              String,
    val text:               String,
    val postedAt:           Long,
    val sbnKey:             String,
    val replyPendingIntent: android.app.PendingIntent? = null,
    val replyRemoteInputs:  List<android.app.RemoteInput> = emptyList(),
) {
    /** True when this notification has a reply action that can be triggered programmatically. */
    val canReply: Boolean get() = replyPendingIntent != null && replyRemoteInputs.isNotEmpty()
}
