package com.jarvis.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * Application subclass — declared in AndroidManifest as android:name=".JarvisApp".
 *
 * Responsibilities right now:
 *  - Create the notification channel that JarvisService's persistent
 *    notification will live in. Channels must exist before any notification
 *    in that channel is posted, so doing it here (at app start) is safe.
 *
 * In future sessions this is also where we'll initialise the Room/SQLite
 * database and any app-wide singletons.
 */
class JarvisApp : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "jarvis_service_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        // NotificationChannels were introduced in Android 8.0 (API 26).
        // Our minSdk IS 26, so this branch always executes, but the check
        // is idiomatic and keeps lint happy.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                // IMPORTANCE_LOW = no sound, no heads-up — persistent but silent.
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
