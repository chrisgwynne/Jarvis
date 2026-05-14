package com.jarvis.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.core.state.JarvisState
import com.jarvis.assistant.runtime.JarvisRuntime
import com.jarvis.assistant.ui.MainActivity
import com.jarvis.assistant.util.SettingsStore
import com.jarvis.assistant.reporting.github.autoReporting
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JarvisService — the persistent foreground service that owns JarvisRuntime.
 *
 * ARCHITECTURE:
 *   Service → JarvisRuntime → StateMachine + ToolRegistry + Memory + Audio
 *
 * UI CONTRACT (unchanged from previous implementation):
 *   Broadcasts BROADCAST_STATE_CHANGED with EXTRA_STATE = legacy state name (IDLE/LISTENING/PROCESSING/SPEAKING).
 *   Broadcasts BROADCAST_SERVICE_STARTED and BROADCAST_SERVICE_STOPPED.
 *   These are read by MainScreen.kt via LocalBroadcastManager.
 */
class JarvisService : Service() {

    companion object {
        private const val TAG = "JarvisService"
        private const val NOTIFICATION_ID = 1001

        // Intent actions TO the service
        const val ACTION_START             = "com.jarvis.assistant.ACTION_START"
        const val ACTION_STOP              = "com.jarvis.assistant.ACTION_STOP"
        const val ACTION_MANUAL_TRIGGER    = "com.jarvis.assistant.ACTION_MANUAL_TRIGGER"
        const val ACTION_SILENCE           = "com.jarvis.assistant.ACTION_SILENCE"
        const val ACTION_REMINDER_TRIGGER  = "com.jarvis.assistant.ACTION_REMINDER_TRIGGER"
        const val ACTION_LOCATION_REMINDER = "com.jarvis.assistant.LOCATION_REMINDER"
        /**
         * Programmatically end the current outgoing call.
         * Can be triggered from a notification action, overlay button, or
         * any other non-voice UI path.  Routes to [JarvisRuntime.endActiveCall].
         * No-ops gracefully if no outgoing call is in progress.
         */
        const val ACTION_END_CALL          = "com.jarvis.assistant.ACTION_END_CALL"
        /**
         * Temporarily pause wake-word detection.
         * Used by SettingsViewModel before playing a TTS voice sample so the
         * sample audio doesn't trigger the pipeline.
         */
        const val ACTION_SUPPRESS_WAKE     = "com.jarvis.assistant.ACTION_SUPPRESS_WAKE"
        /**
         * Restore wake-word detection after sample playback completes.
         */
        const val ACTION_RESTORE_WAKE      = "com.jarvis.assistant.ACTION_RESTORE_WAKE"
        /**
         * Speak a test phrase through the specified TTS voice.
         * Passes [EXTRA_VOICE_NAME] so the engine switches to that voice before speaking.
         */
        const val ACTION_TTS_TEST          = "com.jarvis.assistant.ACTION_TTS_TEST"
        /**
         * Apply a new TTS voice to the running engine without restarting the service.
         * Passes [EXTRA_VOICE_NAME].
         */
        const val ACTION_APPLY_VOICE       = "com.jarvis.assistant.ACTION_APPLY_VOICE"
        /**
         * Settings UI toggled "Register as OpenClaw node".  Boolean
         * [EXTRA_ENABLED] tells the service to start (true) or stop (false)
         * the persistent gateway WebSocket.  Without this action, toggling
         * the switch was purely cosmetic — the WebSocket was only started
         * once at service onCreate.
         */
        const val ACTION_OPENCLAW_NODE_TOGGLE = "com.jarvis.assistant.ACTION_OPENCLAW_NODE_TOGGLE"

        const val EXTRA_REMINDER_ID    = "reminder_id"
        const val EXTRA_REMINDER_LABEL = "reminder_label"
        const val EXTRA_VOICE_NAME     = "voice_name"
        const val EXTRA_ENABLED        = "enabled"

        // Broadcasts FROM the service to the UI
        const val BROADCAST_STATE_CHANGED   = "com.jarvis.assistant.STATE_CHANGED"
        const val BROADCAST_LOG_ENTRY       = "com.jarvis.assistant.LOG_ENTRY"
        const val BROADCAST_SERVICE_STARTED = "com.jarvis.assistant.SERVICE_STARTED"
        const val BROADCAST_SERVICE_STOPPED = "com.jarvis.assistant.SERVICE_STOPPED"
        const val EXTRA_STATE    = "state"
        const val EXTRA_LOG_TEXT = "log_text"

        // ── Static helper methods (called from UI) ─────────────────────────
        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, JarvisService::class.java).apply { action = ACTION_START }
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, JarvisService::class.java).apply { action = ACTION_STOP }
            )
        }

        fun manualTrigger(context: Context) {
            context.startService(
                Intent(context, JarvisService::class.java).apply { action = ACTION_MANUAL_TRIGGER }
            )
        }

        fun silence(context: Context) {
            context.startService(
                Intent(context, JarvisService::class.java).apply { action = ACTION_SILENCE }
            )
        }

        /** End an active outgoing call if one is in progress. Non-blocking. */
        fun endCall(context: Context) {
            context.startService(
                Intent(context, JarvisService::class.java).apply { action = ACTION_END_CALL }
            )
        }

        /** Pause wake detection (call before TTS sample playback). */
        fun suppressWake(context: Context) {
            context.startService(
                Intent(context, JarvisService::class.java).apply { action = ACTION_SUPPRESS_WAKE }
            )
        }

        /** Resume wake detection (call when TTS sample playback finishes). */
        fun restoreWake(context: Context) {
            context.startService(
                Intent(context, JarvisService::class.java).apply { action = ACTION_RESTORE_WAKE }
            )
        }

        /**
         * Trigger the OpenClaw node WebSocket to (re)start when [enabled]
         * is true, or stop cleanly when false.  Called from
         * `SettingsViewModel.setOpenClawNodeEnabled` so flipping the toggle
         * actually changes the runtime behaviour instead of only writing
         * the setting.
         */
        fun toggleOpenClawNode(context: Context, enabled: Boolean) {
            context.startService(
                Intent(context, JarvisService::class.java).apply {
                    action = ACTION_OPENCLAW_NODE_TOGGLE
                    putExtra(EXTRA_ENABLED, enabled)
                }
            )
        }

        /** Switch the live TTS engine to [voiceName] without restarting the service. */
        fun applyVoice(context: Context, voiceName: String) {
            context.startService(
                Intent(context, JarvisService::class.java).apply {
                    action = ACTION_APPLY_VOICE
                    putExtra(EXTRA_VOICE_NAME, voiceName)
                }
            )
        }

        /** Speak a test phrase via [voiceName] (switches to it first if needed). */
        fun testTts(context: Context, voiceName: String) {
            context.startService(
                Intent(context, JarvisService::class.java).apply {
                    action = ACTION_TTS_TEST
                    putExtra(EXTRA_VOICE_NAME, voiceName)
                }
            )
        }

        /**
         * Returns true if JarvisService is currently running.
         * Used by MainScreen to initialise the start/stop button state correctly
         * when the user reopens the app without the service having been stopped.
         *
         * Backed by a process-wide AtomicBoolean rather than
         * ActivityManager.getRunningServices() — the latter is deprecated
         * since API 26 and throws SecurityException on some OEM ROM builds.
         * The flag is set in onCreate / cleared in onDestroy, so it is
         * accurate for the live process and trivially zero-cost to read.
         */
        private val running = AtomicBoolean(false)

        /**
         * Process-wide reference to the live [JarvisRuntime] when the
         * service is up.  Read-only — settings UI uses this for diagnostic
         * queries (e.g. last 10 proactive events) without needing a
         * bound-service connection.  Null when the service hasn't started
         * yet or has already torn down.
         */
        @Volatile
        private var instance: JarvisRuntime? = null

        /** Public accessor mirroring the volatile [instance]. */
        fun runtimeOrNull(): JarvisRuntime? = instance

        fun isRunning(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = running.get()
    }

    // Legacy state enum — kept so MainScreen.kt continues to work unchanged
    enum class State { IDLE, LISTENING, PROCESSING, SPEAKING }

    private var runtime: JarvisRuntime? = null

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + autoReporting("service")
    )
    private val runtimeDeferred = CompletableDeferred<JarvisRuntime>()

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        running.set(true)
        val t0 = System.currentTimeMillis()
        Log.d(TAG, "onCreate")
        // NOTE: no PARTIAL_WAKE_LOCK here.  The foreground-service with
        // FGS_TYPE=microphone already keeps the CPU alive while audio is
        // recording, and the wake-word / pipeline coroutines run short-lived
        // work that finishes before the device can sleep.  A permanent
        // wakelock held for the service's lifetime prevented deep sleep and
        // was the single biggest battery drain in long sessions.  If a
        // specific branch ever needs the CPU awake outside of audio capture,
        // acquire a scoped, timed lock at that site — never here.
        // Android 10+ (API 29+): use the 3-arg form when foregroundServiceType is declared.
        // Only include a type flag when the corresponding runtime permission is actually
        // granted — on a fresh install neither RECORD_AUDIO nor CAMERA may be granted yet,
        // and passing an ungranted type throws SecurityException on Android 14+ (API 34+).
        //
        // KEY EDGE CASE (issues #19-#23, #26-#28):
        //   If NEITHER permission is granted, [serviceType] is FOREGROUND_SERVICE_TYPE_NONE (0).
        //   On Android 14+ the manifest declares foregroundServiceType="microphone|camera",
        //   so calling startForeground(id, notif, 0) throws
        //   MissingForegroundServiceTypeException, which the framework reports as
        //   "Unable to create service ... : java...".
        //   We fall back to the 2-arg form in that case and wrap the call in try/catch
        //   so a permission-denied launch fails gracefully rather than crashing the process.
        // ── Foreground-service start sequence ────────────────────────────────
        // Two-stage defensive ladder, in priority order:
        //
        //   1. TYPED — startForeground(id, notif, MICROPHONE|CAMERA) using
        //      whatever runtime permissions are currently granted.
        //
        //   2. UNTYPED — startForeground(id, notif), the 2-arg form, when (1)
        //      throws a SecurityException.  This happens on Android 14 (API 34+)
        //      when the app is started from a background context — even with
        //      RECORD_AUDIO granted, the OS demands an "eligible state" for
        //      a microphone FGS, which the boot receiver / deferred start
        //      does not satisfy.  The 2-arg form keeps the service alive so
        //      the user can open the app (foreground) and the service can be
        //      promoted later; without it the process crashes outright.
        //
        // If BOTH paths throw we stop the service cleanly so the framework
        // does not loop on `Unable to create service…`.
        val startedOk: Boolean = tryStartForegroundSequence()
        if (!startedOk) return

        serviceScope.launch {
            try {
                Log.d(TAG, "Initializing JarvisRuntime on background thread...")
                val startTime = System.currentTimeMillis()
                val settings = SettingsStore(this@JarvisService)
                val r = withContext(Dispatchers.IO) {
                    val instance = JarvisRuntime(
                        context      = this@JarvisService,
                        settings     = settings,
                        onStateChange = { jarvisState ->
                            // Map new state → legacy broadcast for the UI
                            broadcastState(jarvisState.toLegacyState())
                        }
                    )
                    instance.initialize()
                    instance
                }
                val initMs = System.currentTimeMillis() - startTime
                Log.i(TAG, "startup: JarvisRuntime initialized in ${initMs}ms (since onCreate: ${System.currentTimeMillis() - t0}ms)")
                runtime = r
                instance = r           // expose to companion-object accessor
                runtimeDeferred.complete(r)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize JarvisRuntime", e)
                // Service-startup failure is a blocker for every feature in
                // the app.  Escalate to a GitHub issue (deduped per
                // fingerprint + rate-limited globally).  The reporter is a
                // no-op when the feature flag is off or no token is set.
                com.jarvis.assistant.reporting.github.IssueReporter.get()?.reportFatal(
                    subsystem = "service_startup",
                    category  = "RUNTIME_INIT_FAILED",
                    message   = "JarvisRuntime failed to initialize — service stopping.",
                    throwable = e
                )
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")

        val action = intent?.action

        if (action == ACTION_STOP) {
            serviceScope.launch {
                try {
                    val r = if (runtimeDeferred.isCompleted) runtimeDeferred.await() else null
                    r?.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping runtime", e)
                } finally {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            return START_NOT_STICKY
        }

        serviceScope.launch {
            try {
                val r = runtimeDeferred.await()
                processAction(r, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error waiting for runtime to process action $action", e)
            }
        }

        return START_STICKY
    }

    private fun processAction(r: JarvisRuntime, intent: Intent?) {
        when (intent?.action) {
            ACTION_MANUAL_TRIGGER   -> r.triggerManually()
            ACTION_SILENCE          -> r.silence()
            ACTION_END_CALL         -> r.endActiveCall()
            ACTION_SUPPRESS_WAKE    -> r.suppressWakeDetection()
            ACTION_RESTORE_WAKE     -> r.restoreWakeDetection()
            ACTION_APPLY_VOICE      -> {
                val voice = intent.getStringExtra(EXTRA_VOICE_NAME) ?: return
                r.applyVoice(voice)
            }
            ACTION_TTS_TEST         -> {
                val voice = intent.getStringExtra(EXTRA_VOICE_NAME) ?: return
                r.testSpeak(voice)
            }
            ACTION_REMINDER_TRIGGER -> {
                val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
                if (reminderId >= 0L) r.onReminderTriggered(reminderId)
            }
            ACTION_LOCATION_REMINDER -> {
                val label = intent.getStringExtra(EXTRA_REMINDER_LABEL) ?: return
                r.speakLocationReminder(label)
            }
            ACTION_OPENCLAW_NODE_TOGGLE -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
                Log.d(TAG, "[OPENCLAW_NODE_TOGGLE] enabled=$enabled")
                r.setOpenClawNodeEnabled(enabled)
            }
            else -> {
                // ACTION_START or null (system restarted us)
                broadcast(BROADCAST_SERVICE_STARTED)
                r.start()
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        serviceScope.cancel()
        runtime?.stop()  // stop() is idempotent; safe even if ACTION_STOP path already fired
        runtime = null
        instance = null  // clear companion accessor so UI sees "no runtime"
        running.set(false)
        broadcast(BROADCAST_SERVICE_STOPPED)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Foreground start helper ───────────────────────────────────────────────

    /**
     * Run the [TYPED → UNTYPED → stopSelf] ladder.  Returns true once any
     * `startForeground` call has succeeded; false when both paths have
     * failed and the service has stopped itself.
     */
    private fun tryStartForegroundSequence(): Boolean {
        // STAGE 1 — typed startForeground.
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                var serviceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    serviceType = serviceType or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                if (checkSelfPermission(android.Manifest.permission.CAMERA) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    serviceType = serviceType or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
                if (serviceType == android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE) {
                    Log.w(TAG, "[SERVICE_NO_FGS_TYPE] No mic/camera permission yet — " +
                        "starting as plain foreground service; UI must request permissions")
                    startForeground(NOTIFICATION_ID, buildNotification())
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification(), serviceType)
                }
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            Log.d(TAG, "[SERVICE_START] typed startForeground ok")
            return true
        } catch (e: Throwable) {
            // Android 14+ throws SecurityException when starting a microphone
            // FGS from a background context even with RECORD_AUDIO granted.
            // Other failure modes: MissingForegroundServiceTypeException,
            // ForegroundServiceStartNotAllowedException, IllegalStateException.
            Log.w(TAG, "[SERVICE_FGS_TYPED_FAILED] ${e.javaClass.simpleName}: ${e.message} — " +
                "falling back to untyped foreground service")
        }

        // STAGE 2 — untyped fallback.  Service stays alive but can't claim
        // microphone/camera; the runtime / UI must promote it later by calling
        // startForegroundService(...) again from a foreground state.
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            Log.w(TAG, "[SERVICE_START_UNTYPED] Running without FGS type — " +
                "mic/camera tools will be unavailable until app is opened from launcher")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "[SERVICE_FGS_FAILED] Untyped fallback also threw — stopping service", e)
            try {
                com.jarvis.assistant.reporting.github.IssueReporter.get()?.reportFatal(
                    subsystem = "service_startup",
                    category  = "FOREGROUND_SERVICE_START_FAILED",
                    message   = "Both typed and untyped startForeground threw on " +
                        "JarvisService.onCreate.",
                    throwable = e
                )
            } catch (_: Throwable) {
                // reporter is best-effort; never throw from a recover path
            }
            running.set(false)
            stopSelf()
            return false
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, JarvisApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ── Broadcast helpers ─────────────────────────────────────────────────────

    private fun broadcastState(legacyStateName: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(BROADCAST_STATE_CHANGED).putExtra(EXTRA_STATE, legacyStateName)
        )
    }

    private fun broadcast(action: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(action))
    }

    fun broadcastLogEntry(text: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(BROADCAST_LOG_ENTRY).putExtra(EXTRA_LOG_TEXT, text)
        )
    }

}
