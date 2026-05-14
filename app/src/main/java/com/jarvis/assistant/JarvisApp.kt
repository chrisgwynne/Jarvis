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

        /**
         * Process-wide singleton so the Settings UI and JarvisRuntime both
         * read / write through the same store.  Initialised in [onCreate];
         * never null after the Application object is constructed.
         */
        @Volatile
        lateinit var featureFlagStore: com.jarvis.assistant.voice.FeatureFlagStore
            private set

        /**
         * Process-wide Proactivity settings repository — same pattern as
         * [featureFlagStore].  Backed by [com.jarvis.assistant.util.SettingsStore],
         * exposes a [kotlinx.coroutines.flow.StateFlow] of the current
         * [com.jarvis.assistant.proactive.settings.ProactivitySettings] so
         * the UI and the [com.jarvis.assistant.proactive.settings.ProactivityGate]
         * see the same value at the same time.
         */
        @Volatile
        lateinit var proactivitySettings
            : com.jarvis.assistant.proactive.settings.ProactivitySettingsRepository
            private set

        /**
         * Todoist integration settings — same pattern as [featureFlagStore]
         * and [proactivitySettings].  Read by the Settings UI and by
         * [com.jarvis.assistant.todoist.TodoistReminderRouter].
         */
        @Volatile
        lateinit var todoistSettings
            : com.jarvis.assistant.todoist.TodoistSettingsRepository
            private set

        /**
         * Scheduled-reminders settings — backs the
         * [com.jarvis.assistant.proactive.scheduled.ScheduledReminderEngine]
         * AND the matching settings rows on the Proactivity screen.
         */
        @Volatile
        lateinit var scheduledReminderSettings
            : com.jarvis.assistant.proactive.scheduled.ScheduledReminderSettingsRepository
            private set
    }

    override fun onCreate() {
        super.onCreate()
        // Load any persisted feature-flag overrides BEFORE any other subsystem
        // reads `VoiceFeatureFlags.isEnabled(...)`.  Cheap: a single
        // SharedPreferences open + a small map mirror.
        featureFlagStore = com.jarvis.assistant.voice.FeatureFlagStore(this).also {
            it.loadAtStartup()
        }
        // Same lifecycle for the Proactivity repository — a SettingsStore
        // open + an immutable snapshot.  The Settings UI binds against its
        // StateFlow, the runtime constructs a ProactivityGate against the
        // same instance, so reads stay coherent.
        proactivitySettings = com.jarvis.assistant.proactive.settings
            .ProactivitySettingsRepository(
                com.jarvis.assistant.util.SettingsStore(this)
            )
        // Todoist repository wired to the same SettingsStore.  Cheap —
        // EncryptedSharedPreferences open is amortised across all repos.
        todoistSettings = com.jarvis.assistant.todoist.TodoistSettingsRepository(
            com.jarvis.assistant.util.SettingsStore(this)
        )
        scheduledReminderSettings = com.jarvis.assistant.proactive.scheduled
            .ScheduledReminderSettingsRepository(
                com.jarvis.assistant.util.SettingsStore(this)
            )
        createNotificationChannel()
        // Install the GitHub issue reporter FIRST so it wraps the thread
        // default uncaught-exception handler before any other subsystem has a
        // chance to misbehave.  Install is cheap (no network, no disk scan);
        // the background drain of pending reports runs on its own IO scope.
        com.jarvis.assistant.reporting.github.IssueReporter.install(this)
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
