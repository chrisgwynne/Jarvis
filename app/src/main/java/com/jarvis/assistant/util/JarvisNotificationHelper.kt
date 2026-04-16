package com.jarvis.assistant.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlin.math.abs

/**
 * JarvisNotificationHelper — centralised helper for posting system notifications.
 *
 * Channels are created lazily on first use.  The object is thread-safe because
 * [ensureChannels] is idempotent and [NotificationManager.createNotificationChannel]
 * is safe to call from any thread.
 *
 * Channel IDs:
 *  - [CHANNEL_ALERTS]    "jarvis_alerts"    — general proactive alerts
 *  - [CHANNEL_REMINDERS] "jarvis_reminders" — user-created reminders and timers
 */
object JarvisNotificationHelper {

    private const val CHANNEL_ALERTS    = "jarvis_alerts"
    private const val CHANNEL_REMINDERS = "jarvis_reminders"

    /** Base offset so alert IDs never collide with system or other Jarvis IDs. */
    private const val ALERT_ID_BASE    = 2000
    /** Base offset for reminder IDs. */
    private const val REMINDER_ID_BASE = 3000

    // ── Channel setup ─────────────────────────────────────────────────────────

    /**
     * Create both notification channels if they do not already exist.
     * Safe to call multiple times — Android is a no-op for duplicate channel IDs.
     */
    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // "jarvis_alerts" — general proactive alerts, default importance
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                "Jarvis Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )

        // "jarvis_reminders" — time-sensitive reminders and timers, high importance + vibration
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                "Reminders & Timers",
                NotificationManager.IMPORTANCE_HIGH
            ).also { ch ->
                ch.enableVibration(true)
                ch.vibrationPattern = longArrayOf(0, 250, 150, 250)
            }
        )
    }

    // ── Notification ID helper ────────────────────────────────────────────────

    /**
     * Derive a stable, non-zero notification ID from a title string.
     * Using abs() of hashCode avoids negative IDs; clamping to the base
     * range prevents collisions with other Jarvis notifications.
     */
    private fun alertId(title: String): Int    = ALERT_ID_BASE    + (abs(title.hashCode()) % 1000)
    private fun reminderId(title: String): Int = REMINDER_ID_BASE + (abs(title.hashCode()) % 1000)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Post a general proactive alert notification on the [CHANNEL_ALERTS] channel.
     *
     * @param context Application or service context.
     * @param title   Short notification title shown in bold.
     * @param body    Expanded notification body text.
     */
    fun postProactiveAlert(context: Context, title: String, body: String) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(alertId(title), notification)
    }

    /**
     * Post a high-priority reminder or timer notification on the [CHANNEL_REMINDERS] channel.
     *
     * @param context Application or service context.
     * @param title   Short notification title (e.g. "Reminder: Call dentist").
     * @param body    Expanded body text (e.g. "Time for: Call dentist").
     */
    fun postReminder(context: Context, title: String, body: String) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(longArrayOf(0, 250, 150, 250))
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(reminderId(title), notification)
    }

    /**
     * Post a missed-call alert using [postProactiveAlert].
     *
     * @param context    Application or service context.
     * @param callerName Display name of the caller, or null if unavailable.
     */
    fun postMissedCallAlert(context: Context, callerName: String?) {
        val displayName = callerName ?: "unknown"
        val title       = "Missed call"
        val body        = "Missed call from $displayName"
        postProactiveAlert(context, title, body)
    }
}
