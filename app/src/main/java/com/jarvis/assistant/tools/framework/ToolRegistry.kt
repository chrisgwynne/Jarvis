package com.jarvis.assistant.tools.framework

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.memory.MemoryRetriever
import com.jarvis.assistant.reminders.ReminderRepository
import com.jarvis.assistant.runtime.reference.LastActionStore
import com.jarvis.assistant.tools.reference.RepeatLastActionTool
import com.jarvis.assistant.tools.reference.UndoLastActionTool
import com.jarvis.assistant.tools.ContactLookup
import com.jarvis.assistant.tools.WebSearch
import com.jarvis.assistant.tools.device.AlarmTool
import com.jarvis.assistant.tools.device.CallTool
import com.jarvis.assistant.tools.device.FlashlightTool
import com.jarvis.assistant.tools.device.MemoryRecallTool
import com.jarvis.assistant.memory.db.JarvisDatabase
import com.jarvis.assistant.shopping.ShoppingRepository
import com.jarvis.assistant.tools.device.CalendarTool
import com.jarvis.assistant.tools.device.DailyBriefingTool
import com.jarvis.assistant.tools.device.HelpTool
import com.jarvis.assistant.tools.device.ImageGenerationTool
import com.jarvis.assistant.tools.device.MediaControlTool
import com.jarvis.assistant.tools.device.MemoryStatsTool
import com.jarvis.assistant.tools.device.OpenAppTool
import com.jarvis.assistant.tools.device.ClearNotificationsTool
import com.jarvis.assistant.tools.device.ReadNotificationsTool
import com.jarvis.assistant.tools.device.ShoppingListTool
import com.jarvis.assistant.tools.device.SmsTool
import com.jarvis.assistant.tools.device.TimerTool
import com.jarvis.assistant.tools.device.VoiceShortcutTool
import com.jarvis.assistant.tools.device.VolumeTool
import com.jarvis.assistant.tools.device.WhatsAppTool
import com.jarvis.assistant.tools.web.WebSearchTool
import com.jarvis.assistant.tools.web.WeatherTool
import com.jarvis.assistant.location.CurrentLocationProvider
import com.jarvis.assistant.util.SettingsStore
import com.jarvis.assistant.audio.recording.AudioRecordingManager
import com.jarvis.assistant.audio.recording.RecordingState
import com.jarvis.assistant.audio.recording.RecordingTranscriber
import com.jarvis.assistant.camera.CameraCaptureManager
import com.jarvis.assistant.camera.VisionClient
import com.jarvis.assistant.tools.device.AnalyzeCameraViewTool
import com.jarvis.assistant.tools.device.LookAtThisIntentHandler
import com.jarvis.assistant.vision.ScreenObservationRepository
import com.jarvis.assistant.vision.ScreenshotCaptureService
import com.jarvis.assistant.vision.VisionScreenAnalyzer
import com.jarvis.assistant.tools.device.DirectionsTool
import com.jarvis.assistant.tools.device.NavigateTool
import com.jarvis.assistant.tools.device.NearestPlaceTool
import com.jarvis.assistant.tools.device.ReadScreenTool
import com.jarvis.assistant.tools.device.TapScreenTool
import com.jarvis.assistant.tools.device.WhereAmITool
import com.jarvis.assistant.maps.DirectionsCoordinator
import com.jarvis.assistant.maps.MapsCommandRouter
import com.jarvis.assistant.maps.MapsIntentHandler
import com.jarvis.assistant.maps.PlacesSearchCoordinator
import com.jarvis.assistant.call.OutgoingCallController
import com.jarvis.assistant.tools.device.AudioRecordingTool
import com.jarvis.assistant.tools.device.CameraCaptureTool
import com.jarvis.assistant.tools.device.LocationReminderTool
import com.jarvis.assistant.shortcuts.VoiceShortcutRepository
import com.jarvis.assistant.tools.device.ConversationExportTool
import com.jarvis.assistant.tools.device.EmailTool
import com.jarvis.assistant.tools.device.EndCallTool
import com.jarvis.assistant.tools.smart.SmartHomeTool
import com.jarvis.assistant.tools.web.MusicSearchTool
import com.jarvis.assistant.tools.device.ReportIssueTool

/**
 * ToolRegistry — ordered list of tools; first match wins.
 *
 * Ordering matters:
 *   High-specificity tools (WhatsApp, Call, SMS) must come before
 *   low-specificity ones (OpenApp, WebSearch) to prevent misrouting.
 */
// Public primary constructor so JVM unit tests can build a minimal registry
// with stub tools.  Production code still uses [buildDefault] which
// resolves every dependency from the live runtime; never call this directly
// outside of tests.
@androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PACKAGE_PRIVATE)
class ToolRegistry constructor(
    private val tools: List<Tool>,
    /** Held so [release] can stop an in-progress recording on service teardown. */
    private val recordingManager: AudioRecordingManager? = null
) {
    /**
     * Release resources that must be freed when the service stops.
     *
     * Currently: stops any active [AudioRecordingManager] session so the OS
     * MediaRecorder releases the microphone.  Without this, a recording that is
     * still running when the service is stopped keeps the mic locked and prevents
     * [WakeWordDetector] from starting on the next service launch.
     */
    fun release() {
        recordingManager?.let { rm ->
            if (rm.state == RecordingState.RECORDING) {
                Log.i(TAG, "Service stopping with recording active — stopping recorder to release mic")
                rm.stop()
            }
        }
    }

    companion object {
        private const val TAG = "ToolRegistry"

        /**
         * Build the default registry for production use.
         *
         * @param memoryRetriever  Optional — if provided, [MemoryRecallTool] is
         *   included so Jarvis can answer questions about past conversations.
         */
        fun buildDefault(
            context: Context,
            settings: SettingsStore,
            memoryRetriever: MemoryRetriever? = null,
            reminderRepository: ReminderRepository? = null,
            outgoingCallController: OutgoingCallController? = null,
            locationProvider: CurrentLocationProvider? = null,
            llmRouter: com.jarvis.assistant.llm.LlmRouter? = null,
            lastActionStore: LastActionStore? = null,
            actionLedger: com.jarvis.assistant.core.decisions.ActionLedger? = null,
            routineRepository: com.jarvis.assistant.core.routines.RoutineRepository? = null,
            recentToolCallBuffer: com.jarvis.assistant.core.routines.RecentToolCallBuffer? = null,
            /**
             * Lazy because PlanRunner itself takes a ToolRegistry — resolving
             * the runner at execute time breaks the circular dependency.
             */
            planRunnerProvider: () -> com.jarvis.assistant.runtime.plan.PlanRunner? = { null },
            expectationStore: com.jarvis.assistant.core.presence.ExpectationStore? = null,
            /**
             * Tier-A2 consolidation: every consumer (SmsTool, WhatsAppTool,
             * CallTool, TranscriptCorrector, FollowUpCoordinator) must share
             * the same [ContactLookup] so the address-book load + Jaro-Winkler
             * cache is computed once.  Defaults to a fresh instance for back-
             * compat when no caller passes one in.
             */
            sharedContacts: ContactLookup? = null,
            /**
             * OpenClaw settings repo — required by [OpenClawStatusTool] so the
             * user can ask "are you connected to OpenClaw?" without going
             * anywhere near the LLM.  When null, the status tool is omitted
             * and the question falls through to the LLM (legacy behaviour).
             */
            openClawRepo: com.jarvis.assistant.remote.openclaw.OpenClawSettingsRepository? = null,
            /**
             * Profile memory service — required by [PersonalFactTool] to
             * answer direct identity questions ("what's my name?") by
             * reading stored profile facts rather than asking the LLM,
             * which previously bled adjacent facts ("I love you").
             */
            profileMemory: com.jarvis.assistant.memory.ProfileMemoryService? = null,
        ): ToolRegistry {
            val contacts = sharedContacts ?: run {
                android.util.Log.d(TAG, "[CONTACT_LOOKUP_SHARED_INSTANCE_CREATED] " +
                    "buildDefault fallback path — no shared lookup passed in")
                ContactLookup(context)
            }
            if (sharedContacts != null) {
                android.util.Log.d(TAG, "[CONTACT_LOOKUP_SHARED_INSTANCE_USED] tools=registry")
            }
            val search   = WebSearch()

            // Shared camera + recording instances — one per registry lifetime
            val cameraCapture    = CameraCaptureManager(context)
            val visionClient     = VisionClient(settings)
            val recordingManager = AudioRecordingManager(context)
            val transcriber      = RecordingTranscriber(settings)

            return ToolRegistry(
                tools = buildList {
                    // LIVE_LOCATION intent MUST land first.  "Where am I?" /
                    // "what's my location?" are dedicated live-GPS queries; the
                    // spec explicitly forbids any other tool (memory, saved
                    // home, web search) from answering them.  Registering the
                    // tool at index 0 guarantees that.
                    locationProvider?.let { add(WhereAmITool(context, it)) }

                    // End-call MUST precede CallTool so "end call" / "hang up"
                    // is not accidentally routed to the outgoing-call tool.
                    outgoingCallController?.let { add(EndCallTool(it)) }
                    // OpenClaw self-introspection: "are you connected to
                    // OpenClaw?" / "ping OpenClaw" / "OpenClaw status".
                    // Registered early so the question never reaches the LLM
                    // (which has no built-in knowledge of OpenClaw).
                    openClawRepo?.let {
                        add(com.jarvis.assistant.tools.device.OpenClawStatusTool(it))
                    }
                    // PersonalFactTool: direct identity queries read straight
                    // from the profile store — never the LLM.  Prevents
                    // adjacent-fact bleed where "what's my name" returned
                    // "I love you".
                    profileMemory?.let {
                        add(com.jarvis.assistant.tools.device.PersonalFactTool(it))
                    }
                    // TimeTool: "what time is it", "what's the date", … must
                    // resolve locally in < 200 ms.  Registered before any tool
                    // whose matcher could otherwise consume time/date phrasing
                    // (AlarmTool / TimerTool / CalendarTool all anchor on
                    // verbs like "set" / "create", so collision is unlikely,
                    // but the explicit ordering guarantees it.)
                    add(com.jarvis.assistant.tools.device.TimeTool())
                    // BatteryTool: "what's my battery", "how much battery",
                    // "is the phone charging".  Reads BatteryManager — no
                    // network, no LLM.
                    add(com.jarvis.assistant.tools.device.BatteryTool(context))
                    // Pure-local utility tools — no network, no Android
                    // permissions, deterministic.  Register early so they
                    // win over the LLM fallback for arithmetic / unit /
                    // stopwatch utterances.
                    add(com.jarvis.assistant.tools.device.CalculatorTool())
                    add(com.jarvis.assistant.tools.device.UnitConversionTool())
                    add(com.jarvis.assistant.tools.device.StopwatchTool())
                    // System toggles: brightness, DND, rotation, screenshot.
                    // Each opens its own special-access settings panel when
                    // the grant is missing — no manifest runtime prompt.
                    add(com.jarvis.assistant.tools.device.BrightnessTool(context))
                    add(com.jarvis.assistant.tools.device.DndTool(context))
                    add(com.jarvis.assistant.tools.device.ScreenRotationTool(context))
                    add(com.jarvis.assistant.tools.device.ScreenshotTool())
                    // Settings panel navigation — pure Intent dispatch.
                    add(com.jarvis.assistant.tools.device.SettingsPanelTool(context))
                    // "Find my phone" — local ring + flashlight strobe.
                    add(com.jarvis.assistant.tools.device.FindPhoneTool(context))
                    // Read-only comms helpers — guarded by READ_SMS and
                    // READ_CALL_LOG runtime permissions.
                    add(com.jarvis.assistant.tools.device.ReadSmsTool(context, contacts))
                    add(com.jarvis.assistant.tools.device.RecentCallsTool(context))
                    // Share-location depends on the location provider.
                    locationProvider?.let {
                        add(com.jarvis.assistant.tools.device.ShareLocationTool(context, contacts, it))
                    }
                    // Calendar create — sibling of the read-only CalendarTool.
                    // Registered before CalendarTool so the more specific
                    // "schedule X at Y" / "add X to calendar" verbs win;
                    // CalendarTool keeps the read-only "what's on my
                    // calendar" path.
                    add(com.jarvis.assistant.tools.device.CalendarCreateTool(context))
                    // High-specificity tools first — order matters.
                    // WhatsApp BEFORE SMS: both now share MessageIntentParser
                    // (which routes by channel), but if a future change ever
                    // makes their matchers overlap again, the explicit-channel
                    // tool must win, so register it first.
                    add(CallTool(context, contacts))
                    add(WhatsAppTool(context, contacts))
                    add(SmsTool(context, contacts))
                    add(EmailTool(context))
                    add(VolumeTool(context))
                    // Music search before MediaControl — "play [track]" must not hit generic play/pause
                    add(MusicSearchTool(context))
                    add(MediaControlTool(context))
                    add(FlashlightTool(context))
                    add(AlarmTool(context))
                    add(TimerTool(context))
                    add(CalendarTool(context))
                    add(SmartHomeTool(settings))
                    // Weather before web search — structured answer, no search cost
                    locationProvider?.let { add(WeatherTool(it)) }

                    // Maps tools — must precede OpenAppTool so "open directions to X"
                    // and "show me Tesco on the map" aren't routed to the generic
                    // app launcher.  All three share one MapsCommandRouter so their
                    // Places + Distance Matrix calls reuse the same HTTP path.
                    if (locationProvider != null) {
                        val mapsIntents      = MapsIntentHandler(context)
                        val placesCoord      = PlacesSearchCoordinator { settings.googleMapsApiKey }
                        val directionsCoord  = DirectionsCoordinator({ settings.googleMapsApiKey }, mapsIntents)
                        val mapsRouter       = MapsCommandRouter(
                            locationProvider = locationProvider,
                            places           = placesCoord,
                            directions       = directionsCoord,
                            intents          = mapsIntents
                        )
                        add(NearestPlaceTool(mapsRouter))
                        add(DirectionsTool(mapsRouter))
                        add(NavigateTool(mapsRouter))
                    }
                    // Memory tools before generic open-app so they aren't misrouted
                    val db = JarvisDatabase.getInstance(context)
                    add(MemoryStatsTool(db.memoryDao(), db.memoryFactDao()))
                    memoryRetriever?.let { add(MemoryRecallTool(it)) }
                    add(DailyBriefingTool(context, reminderRepository))
                    add(LocationReminderTool(context))
                    add(ImageGenerationTool(context, settings))
                    val shoppingRepo = ShoppingRepository(db.shoppingDao())
                    add(ShoppingListTool(shoppingRepo))
                    add(ConversationExportTool(context))
                    add(VoiceShortcutTool(VoiceShortcutRepository(db.voiceShortcutDao())))
                    add(ReadNotificationsTool(context))
                    // Clear AFTER Read so "clear my notifications" doesn't get
                    // shadowed by Read's "read my notifications" pattern (they
                    // don't overlap, but keep related tools adjacent for sanity).
                    add(ClearNotificationsTool(context))
                    add(com.jarvis.assistant.tools.device.ReplyNotificationTool(context))
                    add(com.jarvis.assistant.tools.device.ClipboardTool(context))
                    // Screen vision + actuation (before generic OpenApp)
                    llmRouter?.let { add(ReadScreenTool(it)) }
                    add(TapScreenTool())
                    // Meta Wearables — "look at this" / "take a glasses
                    // photo" via the optional Meta DAT module.  Runs BEFORE
                    // the screen-observation handler so when the user has
                    // glasses enabled AND connected, the glasses path wins;
                    // when wearables are off / disconnected the tool declines
                    // (returns null from matches) so the screen / phone-camera
                    // path remains the default.
                    try {
                        add(com.jarvis.assistant.tools.device.wearables
                            .LookAtThisWearableTool(
                                manager  = com.jarvis.assistant.JarvisApp.metaWearables,
                                settings = { com.jarvis.assistant.JarvisApp
                                    .wearablesSettings.snapshot() },
                            ))
                    } catch (_: Throwable) {
                        // JarvisApp not yet initialised (test harness path) —
                        // skip the wearables tool quietly.  The rest of the
                        // registry is independent.
                    }
                    // "look at this" — screen observation.  Must precede
                    // AnalyzeCameraViewTool so the generic "look at this"
                    // phrase captures the screen (primary user intent),
                    // leaving camera-specific phrasings for the camera tool.
                    // Requires the main LLM pipeline for structured-JSON calls.
                    if (llmRouter != null) {
                        val screenshotCapture = ScreenshotCaptureService(context)
                        val screenAnalyzer    = VisionScreenAnalyzer(visionClient, llmRouter)
                        val screenRepo        = ScreenObservationRepository(db.memoryDao())
                        add(LookAtThisIntentHandler(screenshotCapture, screenAnalyzer, screenRepo))
                    }
                    // Camera + vision tools (before OpenApp to avoid misrouting)
                    add(CameraCaptureTool(context, cameraCapture))
                    // ViewMediaTool + ShareMediaTool — discoverable via
                    // "show me the selfie", "share that photo".  Must
                    // come AFTER CameraCaptureTool so a fresh capture
                    // utterance wins over the view path.
                    add(com.jarvis.assistant.tools.device.ViewMediaTool(context))
                    add(com.jarvis.assistant.tools.device.ShareMediaTool(context))
                    add(AnalyzeCameraViewTool(context, cameraCapture, visionClient, llmRouter))
                    // Audio recording (start/stop/transcribe/summarize)
                    add(AudioRecordingTool(context, recordingManager, transcriber))
                    // AppActionTool — handles parameterised "open Firefox
                    // and search for X", "Google X", "search Maps for Y".
                    // Registered BEFORE the bare OpenAppTool so the
                    // richer forms win; OpenAppTool catches everything
                    // it doesn't handle.
                    add(com.jarvis.assistant.tools.device.apps.AppActionTool(context))
                    add(OpenAppTool(context))
                    add(WebSearchTool(search, settings))
                    // Referential tools — "undo that" / "do the same".  Only
                    // included when a LastActionStore is provided so unit
                    // tests that don't need these can stay lean.
                    lastActionStore?.let {
                        add(UndoLastActionTool(it))
                        add(RepeatLastActionTool(it))
                    }
                    actionLedger?.let {
                        add(com.jarvis.assistant.tools.device.MuteSuggestionTool(it))
                    }
                    // Routine tools: need the repository + buffer + planner.
                    // Gated on all three being present so partial wiring skips
                    // them cleanly in tests.
                    if (routineRepository != null && recentToolCallBuffer != null) {
                        add(com.jarvis.assistant.tools.device.SaveRoutineTool(recentToolCallBuffer, routineRepository))
                        add(com.jarvis.assistant.tools.device.RunRoutineTool(routineRepository, planRunnerProvider))
                        add(com.jarvis.assistant.tools.device.ListRoutinesTool(routineRepository))
                        add(com.jarvis.assistant.tools.device.DeleteRoutineTool(routineRepository))
                    }
                    expectationStore?.let {
                        add(com.jarvis.assistant.tools.device.NoteExpectationTool(it))
                    }
                    add(ReportIssueTool())
                    add(HelpTool { buildCapabilitySummary(settings) })
                },
                recordingManager = recordingManager
            )
        }

        /**
         * Build a spoken capability summary that reflects actual live app state.
         *
         * Evaluated lazily on each "what can you do?" query so it reflects the
         * current provider, OpenClaw state, etc. without a stale hardcoded list.
         */
        private fun buildCapabilitySummary(settings: SettingsStore): String {
            val visionProviders  = setOf("OpenAI", "OpenRouter", "Anthropic")
            val canAnalyseImages = settings.llmProvider in visionProviders
            val canTranscribe    = settings.llmProvider == "OpenAI"

            val caps = buildList {
                add("make calls and end calls")
                add("send SMS and WhatsApp messages")
                add("take photos and selfies")
                if (canAnalyseImages) add("analyse what the camera sees")
                if (com.jarvis.assistant.accessibility.JarvisAccessibilityService.isConnected())
                    add("read what's on screen and tap buttons by name")
                add("set reminders, timers, and alarms")
                add("start and stop audio recordings")
                if (canTranscribe) add("transcribe and summarise recordings")
                add("search the web")
                add("find nearby places and open directions in Google Maps")
                add("control media playback and volume")
                add("open apps")
                add("read and clear your calendar and notifications")
                add("manage your shopping list")
                add("generate images")
                add("give you a daily briefing")
                if (settings.openClawEnabled) add("route complex tasks to a remote assistant")
            }

            val capList = if (caps.size > 1)
                caps.dropLast(1).joinToString(", ") + ", and ${caps.last()}"
            else
                caps.firstOrNull() ?: "answer questions"

            val caveats = buildList {
                if (!canAnalyseImages)
                    add("Image analysis requires OpenAI, OpenRouter, or Anthropic — switch in Settings.")
                if (!canTranscribe)
                    add("Recording transcription requires OpenAI — switch in Settings.")
            }

            return buildString {
                append("Here's what I can do right now: $capList. ")
                append("Just speak naturally — for example: call Mum, take a selfie, ")
                append("start recording, or remind me at 5 pm to leave work.")
                if (caveats.isNotEmpty()) {
                    append(" Note: ")
                    append(caveats.joinToString(" "))
                }
            }
        }
    }

    /**
     * Find the first tool that matches [transcript] and is available
     * given the current [isOnline] state.
     *
     * Returns a Pair(tool, input) or null if nothing matched.
     */
    fun match(transcript: String, isOnline: Boolean): Pair<Tool, ToolInput>? {
        for (tool in tools) {
            // Skip network tools when offline (unless they have a local fallback)
            if (tool.requiresNetwork && !isOnline && !tool.isLocalFallback) continue

            val input = tool.matches(transcript) ?: continue
            Log.d(TAG, "Matched '${tool.name}' for: \"${transcript.take(60)}\"")
            return Pair(tool, input)
        }
        return null
    }

    /**
     * Execute a matched tool, with permission checking.
     * Returns [ToolResult.Failure] if permissions are missing.
     */
    suspend fun execute(
        context: Context,
        tool: Tool,
        input: ToolInput
    ): ToolResult {
        // Permission gate
        val missing = tool.requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            val label = missing.joinToString(", ") { it.substringAfterLast('.') }
            return ToolResult.Failure(
                spokenFeedback = "I need the $label permission to do that. Please grant it in Settings."
            )
        }

        return try {
            tool.execute(input)
        } catch (e: Exception) {
            Log.e(TAG, "Tool '${tool.name}' threw: ${e.message}", e)
            ToolResult.Failure(
                spokenFeedback = "That didn't work. Let me try another way if you want.",
                cause = e
            )
        }
    }

    /** All registered tool names — for debug/logging. */
    val registeredNames: List<String> get() = tools.map { it.name }

    /** Tools available given the current network state. */
    fun available(isOnline: Boolean): List<Tool> =
        tools.filter { isOnline || !it.requiresNetwork || it.isLocalFallback }

    /** Find a tool by its machine [name] (for function-call dispatch). */
    fun findByName(name: String): Tool? = tools.firstOrNull { it.name == name }

    /** Tools that expose a [ToolSchema] for LLM function calling. */
    fun availableSchemas(isOnline: Boolean): List<ToolSchema> =
        available(isOnline).mapNotNull { it.schema() }
}
