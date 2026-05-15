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

        /**
         * Personality — markdown files under assets/personality/ plus
         * the user-visible policy flags.  Read by PromptAssembler (for
         * LLM chat) and the local response template engine.
         */
        @Volatile
        lateinit var personalitySettings
            : com.jarvis.assistant.personality.PersonalitySettingsRepository
            private set
        @Volatile
        lateinit var personalityLoader
            : com.jarvis.assistant.personality.PersonalityProfileLoader
            private set

        /**
         * Meta Wearables (DAT SDK) — optional eyes-and-hands-free
         * module.  Settings repository is always created; the manager
         * picks a backend (stub / mock / real) at construction time
         * based on settings + SDK classpath presence.  The whole
         * subsystem is dormant when the master toggle is OFF.
         */
        @Volatile
        lateinit var wearablesSettings
            : com.jarvis.assistant.wearables.meta.WearablesSettingsRepository
            private set
        @Volatile
        lateinit var metaWearables
            : com.jarvis.assistant.wearables.meta.MetaWearablesManager
            private set

        /**
         * Ambient Intelligence — settings repository and event emitter.
         * The emitter is started/stopped by JarvisRuntime alongside the
         * proactive engine; settings are read by the Settings UI.
         */
        @Volatile
        lateinit var ambientSettings
            : com.jarvis.assistant.ambient.AmbientSettingsRepository
            private set
        @Volatile
        var ambientEmitter
            : com.jarvis.assistant.ambient.AmbientProactiveEventEmitter? = null
            internal set
        @Volatile
        lateinit var preferenceEngine
            : com.jarvis.assistant.preferences.ResponsePreferenceEngine
            internal set

        /**
         * Process-wide visual context store — holds the most recent image/screenshot
         * Jarvis has seen.  Read by the Settings diagnostics screen and written by
         * all vision tools (camera, screenshot, wearable) at runtime.
         */
        @Volatile
        lateinit var visualContextStore
            : com.jarvis.assistant.vision.VisualContextStore
            private set

        /**
         * Tracks the most recently opened app so referential close commands
         * ("close it", "close that") have something to resolve against.
         * Set by [OpenAppTool] on every successful launch; consumed by [CloseAppTool].
         */
        @Volatile
        lateinit var recentAppContextStore
            : com.jarvis.assistant.tools.device.RecentAppContextStore
            private set

        /**
         * Tracks the active Maps navigation destination + mode set by
         * [NavigateTool] or [DirectionsTool].  Consumed by
         * [MapsNavigationFollowupTool] for "go", "start it", "switch to walking", etc.
         */
        @Volatile
        lateinit var mapsNavigationContextStore
            : com.jarvis.assistant.maps.MapsNavigationContextStore
            private set

        // ── Session Intelligence context stores ──────────────────────────────

        @Volatile
        lateinit var recentCalendarContextStore
            : com.jarvis.assistant.session.context.RecentCalendarContextStore
            private set

        @Volatile
        lateinit var recentMessageContextStore
            : com.jarvis.assistant.session.context.RecentMessageContextStore
            private set

        @Volatile
        lateinit var recentHaContextStore
            : com.jarvis.assistant.session.context.RecentHomeAssistantContextStore
            private set

        @Volatile
        lateinit var recentTodoistContextStore
            : com.jarvis.assistant.session.context.RecentTodoistContextStore
            private set

        @Volatile
        lateinit var recentProactiveContextStore
            : com.jarvis.assistant.session.context.RecentProactiveContextStore
            private set

        /** Single source of truth for the current session state. */
        @Volatile
        lateinit var sessionStateEngine
            : com.jarvis.assistant.session.SessionStateEngine
            private set

        // ── Trust & Autonomy ──────────────────────────────────────────────
        @Volatile
        lateinit var autonomySettingsRepo
            : com.jarvis.assistant.trust.AutonomySettingsRepository
            private set

        @Volatile
        lateinit var learnedTrustStore
            : com.jarvis.assistant.trust.LearnedTrustStore
            private set

        @Volatile
        lateinit var autonomyEngine
            : com.jarvis.assistant.trust.AutonomyEngine
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
        personalitySettings = com.jarvis.assistant.personality
            .PersonalitySettingsRepository(
                com.jarvis.assistant.util.SettingsStore(this)
            )
        personalityLoader = com.jarvis.assistant.personality
            .PersonalityProfileLoader(this)
        // Eager load so the first LLM request doesn't pay the asset I/O.
        personalityLoader.load()
        wearablesSettings = com.jarvis.assistant.wearables.meta
            .WearablesSettingsRepository(
                com.jarvis.assistant.util.SettingsStore(this)
            )
        metaWearables = com.jarvis.assistant.wearables.meta
            .MetaWearablesManager(
                context = this,
                settingsProvider = { wearablesSettings.snapshot() },
            )
        ambientSettings = com.jarvis.assistant.ambient.AmbientSettingsRepository(
            com.jarvis.assistant.util.SettingsStore(this)
        )
        // ambientEmitter is created by JarvisRuntime.initialize() and assigned
        // back here via JarvisApp.ambientEmitter = ... so it is always live by
        // the time the Settings UI tries to read diagnostics.
        visualContextStore = com.jarvis.assistant.vision.VisualContextStore()
        recentAppContextStore       = com.jarvis.assistant.tools.device.RecentAppContextStore()
        mapsNavigationContextStore  = com.jarvis.assistant.maps.MapsNavigationContextStore()
        recentCalendarContextStore  = com.jarvis.assistant.session.context.RecentCalendarContextStore()
        recentMessageContextStore   = com.jarvis.assistant.session.context.RecentMessageContextStore()
        recentHaContextStore        = com.jarvis.assistant.session.context.RecentHomeAssistantContextStore()
        recentTodoistContextStore   = com.jarvis.assistant.session.context.RecentTodoistContextStore()
        recentProactiveContextStore = com.jarvis.assistant.session.context.RecentProactiveContextStore()
        sessionStateEngine          = com.jarvis.assistant.session.SessionStateEngine()
        autonomySettingsRepo = com.jarvis.assistant.trust.AutonomySettingsRepository(
            com.jarvis.assistant.util.SettingsStore(this)
        )
        learnedTrustStore = com.jarvis.assistant.trust.LearnedTrustStore(this)
        autonomyEngine    = com.jarvis.assistant.trust.AutonomyEngine(
            settingsRepo  = autonomySettingsRepo,
            learnedStore  = learnedTrustStore,
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
