package com.jarvis.assistant.runtime

import android.content.Context
import android.util.Log
import com.jarvis.assistant.audio.AudioFocusManager
import com.jarvis.assistant.audio.BargeInDetector
import com.jarvis.assistant.audio.BluetoothScoManager
import com.jarvis.assistant.audio.GoogleWakeWordDetector
import com.jarvis.assistant.audio.SpeechCapture
import com.jarvis.assistant.audio.TFLiteWakeWordDetector
import com.jarvis.assistant.audio.TtsEngine
import com.jarvis.assistant.audio.WakeWordDetector
import com.jarvis.assistant.call.CallCoordinator
import com.jarvis.assistant.call.CallEvent
import com.jarvis.assistant.call.OutgoingCallController
import com.jarvis.assistant.call.integration.ContactsPhoneLookupResolver
import com.jarvis.assistant.call.integration.TelecomCallActionExecutor
import com.jarvis.assistant.call.integration.TelephonyCallMonitor
import com.jarvis.assistant.context.ContextEngine
import com.jarvis.assistant.context.Presence
import com.jarvis.assistant.location.CurrentLocationProvider
import com.jarvis.assistant.core.state.JarvisState
import com.jarvis.assistant.core.state.JarvisStateMachine
import com.jarvis.assistant.core.store.DeviceStateStore
import com.jarvis.assistant.llm.LlmRouter
import com.jarvis.assistant.memory.MemoryRetriever
import com.jarvis.assistant.memory.MemorySummarizer
import com.jarvis.assistant.memory.MemoryWriter
import com.jarvis.assistant.followup.FlowResult
import com.jarvis.assistant.followup.FollowUpCoordinator
import com.jarvis.assistant.knowledge.EntityResolver
import com.jarvis.assistant.knowledge.KnowledgeCompiler
import com.jarvis.assistant.knowledge.KnowledgeQueryEngine
import com.jarvis.assistant.knowledge.KnowledgeRepository
import com.jarvis.assistant.knowledge.RetentionPolicy
import com.jarvis.assistant.knowledge.db.entity.KnowledgeSource
import com.jarvis.assistant.memory.ProfileMemoryService
import com.jarvis.assistant.memory.db.JarvisDatabase
import com.jarvis.assistant.orchestration.ConversationAction
import com.jarvis.assistant.orchestration.IntentClassifier
import com.jarvis.assistant.orchestration.MemoryActionHandler
import com.jarvis.assistant.orchestration.ReminderActionHandler
import com.jarvis.assistant.prompt.PromptAssembler
import com.jarvis.assistant.reminders.ReminderRepository
import com.jarvis.assistant.reminders.ReminderScheduler
import com.jarvis.assistant.proactive.AppBatterySource
import com.jarvis.assistant.proactive.AppBrainPredictionSource
import com.jarvis.assistant.proactive.AppCalendarContextSource
import com.jarvis.assistant.proactive.AppCallContextSource
import com.jarvis.assistant.proactive.AppNotificationSource
import com.jarvis.assistant.proactive.AppReminderSource
import com.jarvis.assistant.proactive.AppSpeechStateSource
import com.jarvis.assistant.proactive.ProactiveConfig
import com.jarvis.assistant.proactive.ProactiveEngine
import com.jarvis.assistant.proactive.TtsProactiveDispatcher
import com.jarvis.assistant.conversation.ConversationClassifier
import com.jarvis.assistant.conversation.ConversationIntent
import com.jarvis.assistant.conversation.InterruptionClassifier
import com.jarvis.assistant.conversation.InterruptionType
import com.jarvis.assistant.conversation.ResumableResponse
import com.jarvis.assistant.conversation.ToolUsePolicy
import com.jarvis.assistant.proactive.followup.ConversationalProactiveEngine
import com.jarvis.assistant.proactive.followup.FollowUpExtractor
import com.jarvis.assistant.proactive.followup.FollowUpRepository
import com.jarvis.assistant.proactive.followup.LastSeenTracker
import com.jarvis.assistant.remote.openclaw.OpenClawExecutionResult
import com.jarvis.assistant.remote.openclaw.OpenClawRouter
import com.jarvis.assistant.remote.openclaw.OpenClawSettingsRepository
import com.jarvis.assistant.security.ActionPolicyGate
import com.jarvis.assistant.llm.LlmResult
import com.jarvis.assistant.llm.Message
import com.jarvis.assistant.llm.NetworkClient
import com.jarvis.assistant.tools.ContactLookup
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolRegistry
import com.jarvis.assistant.runtime.DrivingModeManager
import com.jarvis.assistant.util.LatencyTracker
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.util.JarvisNotificationHelper
import com.jarvis.assistant.util.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.jarvis.assistant.speaker.SpeakerEnrollmentManager
import com.jarvis.assistant.speaker.SpeakerIdentityResult
import com.jarvis.assistant.speaker.SpeakerPermissionPolicy
import com.jarvis.assistant.speaker.SpeakerProfileStore
import com.jarvis.assistant.speaker.SpeakerRecognitionCoordinator
import com.jarvis.assistant.speaker.SpeakerSessionContext
import com.jarvis.assistant.speaker.audio.SpeakerAudioCapture
import com.jarvis.assistant.speaker.audio.SpeakerEmbeddingEngine

/**
 * JarvisRuntime — the central orchestrator for the voice pipeline.
 *
 * OWNS:
 *   • JarvisStateMachine   — single source of truth for runtime state
 *   • ToolRegistry         — dispatches commands (device + web + memory recall)
 *   • MemoryWriter/Reader  — persists and retrieves conversation memory
 *   • PromptAssembler      — builds fresh context+memory-injected prompts
 *   • BargeInDetector      — detects speech during TTS and interrupts it
 *   • BluetoothScoManager  — routes audio through a connected BT headset (Phase 4)
 *   • AudioFocusManager    — requests/abandons Android audio focus (Phase 5)
 *   • Audio components     — SpeechCapture, TtsEngine, WakeWordDetector
 *   • CallCoordinator      — handles incoming cellular call interaction
 *
 * PHASES IMPLEMENTED IN THIS FILE:
 *   Phase 2 — PromptAssembler fully owns system prompt; LlmRouter.completeWithMessages()
 *             used so there is no double-assembly of context/memory.
 *   Phase 3 — MemoryRecallTool registered in ToolRegistry; answers recall questions.
 *   Phase 4 — BluetoothScoManager: SCO connected on wake, disconnected on idle.
 *   Phase 5 — AudioFocusManager: TRANSIENT_EXCLUSIVE focus on wake, abandoned on idle.
 *             Focus loss (phone call, etc.) triggers silence() automatically
 *             UNLESS we are inside a call-handling state.
 *   Phase 6 — CallCoordinator: incoming cellular call detection, announcement,
 *             answer/decline interaction, post-call recovery.
 */
class JarvisRuntime(
    private val context: Context,
    private val settings: SettingsStore,
    private val onStateChange: (JarvisState) -> Unit
) {

    companion object {
        private const val TAG                  = "JarvisRuntime"
        private const val FAST_FAIL_THRESHOLD  = 3_000L
        private const val MAX_FAST_FAILS       = 3
        private const val MAX_TOOL_HOPS        = 3   // agentic chain cap per turn

        private val REMEMBER_ME_PATTERN = Regex(
            """remember\s+me|save\s+my\s+(?:voice|profile)|add\s+me""",
            RegexOption.IGNORE_CASE
        )

        private val ENROLL_VOICE_PATTERN = Regex(
            """(?:train|enroll|enrol|learn|teach\s+you)\s+my\s+voice|add\s+(?:my\s+)?voice\s+samples?|improve\s+(?:my\s+)?(?:voice|recognition)""",
            RegexOption.IGNORE_CASE
        )

        private const val SAMPLES_TO_COLLECT = 5
        private val ENROLLMENT_PROMPTS = listOf(
            "hey Jarvis, what's the weather today",
            "set a timer for five minutes please",
            "turn off the kitchen lights",
            "what time does the supermarket close",
            "play some music in the living room"
        )
    }

    // ── Core ──────────────────────────────────────────────────────────────────

    private val machine              = JarvisStateMachine()
    private val locationProvider     = CurrentLocationProvider(context)
    private val contextEngine        = ContextEngine(context, locationProvider)
    private val llmRouter            = LlmRouter(context)
    private val conversationCompressor = com.jarvis.assistant.data.ConversationCompressor(
        store     = llmRouter.conversationStore,
        summarise = llmRouter::completeSilent
    )

    // Room — memory layer
    private lateinit var db              : JarvisDatabase
    private lateinit var memoryWriter    : MemoryWriter
    private lateinit var memoryReader    : MemoryRetriever
    private lateinit var summarizer      : MemorySummarizer
    private lateinit var profileMemory   : ProfileMemoryService
    private lateinit var promptAssembler : PromptAssembler

    // Knowledge system
    private lateinit var knowledgeCompiler: KnowledgeCompiler
    private lateinit var knowledgeQuery   : KnowledgeQueryEngine
    private lateinit var retentionPolicy  : RetentionPolicy
    private var lastCompactionMs = 0L

    // Phase 7 — Orchestration: IntentClassifier + action handlers
    private lateinit var reminderRepo      : ReminderRepository

    // Outgoing call controller — tracks active outgoing calls and provides end-call support.
    private val outgoingCallController = OutgoingCallController(context)

    // Tool registry — Phase 3: MemoryRecallTool injected via memoryReader
    private lateinit var toolRegistry : ToolRegistry
    private lateinit var toolDispatcher  : ToolDispatcher
    private lateinit var memoryHandler     : MemoryActionHandler
    private lateinit var reminderHandler   : ReminderActionHandler

    // Agentic plan runner — confirms multi-step tool calls, journals each step
    // for reverse-order undo.  Pending plan is stored on the runtime so the
    // next user turn ("go" / "no") routes to confirm() before reaching the LLM.
    private lateinit var planRunner   : com.jarvis.assistant.runtime.plan.PlanRunner
    private lateinit var lastActionStore : com.jarvis.assistant.runtime.reference.LastActionStore
    @Volatile private var pendingPlan : com.jarvis.assistant.runtime.plan.Plan? = null

    private val planConfirmRegex = Regex(
        """^\s*(yes|yeah|yep|yup|go|do it|sure|ok(?:ay)?|please do|sounds good|right one)\s*\.?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val planCancelRegex = Regex(
        """^\s*(no|nope|nah|cancel|don't|dont|stop|abort|never mind|nevermind|scrap (?:that|it)|forget it)\s*\.?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val planUndoRegex = Regex(
        """^\s*undo(?:\s+(?:that|the\s+last|last\s+(?:thing|action|plan)))?\s*\.?\s*$""",
        RegexOption.IGNORE_CASE
    )

    // Phase 8 — Context-aware follow-ups
    private lateinit var followUpCoordinator : FollowUpCoordinator

    // Audio pipeline
    private val speechCapture  = SpeechCapture(context)
    private val ttsEngine      = TtsEngine(context)
    private val bargeIn        = BargeInDetector(onBargeIn = ::handleBargeIn)
    private var wakeDetector: WakeWordDetector? = null

    // Proactive awareness engine
    private val callSource        = AppCallContextSource()
    private val speechStateSource = AppSpeechStateSource(machine)
    private lateinit var proactiveEngine   : ProactiveEngine

    // Conversational follow-up + last-seen tracking
    private lateinit var lastSeenTracker   : LastSeenTracker
    private lateinit var followUpRepo      : FollowUpRepository
    private lateinit var convProactiveEngine: ConversationalProactiveEngine

    // OpenClaw — remote routing for complex queries
    private val openClawRouter = OpenClawRouter(OpenClawSettingsRepository(settings))

    // Jarvis Brain — behavioural learning system
    private lateinit var brainEngine: com.jarvis.assistant.brain.BrainEngine


    // Driving mode — detects car Bluetooth + UiMode car dock
    private val drivingModeManager = DrivingModeManager(context).also { mgr ->
        mgr.onDrivingStateChanged = { driving ->
            Log.i(TAG, "Driving mode changed: driving=$driving")
            if (driving) {
                scope.launch { ttsEngine.speak("Driving mode on. I'll keep responses brief.") }
            }
        }
    }

    // Phase 4 — Bluetooth SCO for headset audio routing
    private val bluetoothSco   = BluetoothScoManager(context)

    // Coroutine scope — SupervisorJob so a child failure does not cancel the whole scope
    private val scope          = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Phase 5 — Audio focus.
    private val audioFocus     = AudioFocusManager(context) { isTransient ->
        Log.d(TAG, "Audio focus lost (transient=$isTransient)")
        if (machine.current.isCallState) {
            Log.d(TAG, "Focus lost during call state — suppressing silence()")
            return@AudioFocusManager
        }
        if (!isTransient || machine.isIn<JarvisState.IdleWake>()) {
            Log.d(TAG, "Triggering silence()")
            scope.launch { silence() }
        } else {
            Log.d(TAG, "Transient focus loss during active session — ignoring")
        }
    }

    // Phase 6 — Incoming call handling
    private val callMonitor     = TelephonyCallMonitor(context)
    private val callResolver    = ContactsPhoneLookupResolver(context)
    private val callExecutor    = TelecomCallActionExecutor(context)
    private lateinit var callCoordinator : CallCoordinator

    // Pipeline state
    private var pipelineJob: Job? = null
    private var callEventJob: Job? = null
    private var wakeWordSetupJob: Job? = null
    @Volatile private var pendingCallInfo: CallEvent.IncomingRinging? = null
    private lateinit var sessionId   : String
    private var sessionOpen = false

    // Interruption / resume state — populated by streamAndSpeak() when the user
    // barges in mid-response.  Consumed and cleared on the next user turn.
    @Volatile private var lastInterrupted : ResumableResponse? = null
    // Volatile strings (not StringBuilders) so the barge-in callback (Main thread)
    // and the streaming collector (IO coroutine) never share a mutable object.
    // StringBuilder is not thread-safe; @Volatile String assignments are atomic.
    @Volatile private var currentSpokenSoFar : String = ""
    @Volatile private var currentPendingTail : String = ""
    @Volatile private var currentTurnTranscript : String = ""

    // ── Speaker recognition ────────────────────────────────────────────────────
    private lateinit var speakerStore      : SpeakerProfileStore
    private lateinit var speakerEnrollment : SpeakerEnrollmentManager
    lateinit var speakerCoordinator        : SpeakerRecognitionCoordinator
    /** True once an owner PersonRecord exists (name entered). Gates onboarding prompt. */
    @Volatile private var anyoneRegistered = false
    /** True once any person has voice embeddings. Gates biometric trust-mode bypass. */
    @Volatile private var anyoneEnrolled   = false
    private var sessionSpeaker    = SpeakerSessionContext()
    private var activeCapture     : SpeakerAudioCapture? = null

    init {
        // Most heavy work now moved to initialize()
        Log.d(TAG, "JarvisRuntime constructor complete")
    }

    /**
     * Perform heavy initialization off the main thread.
     * This is called by JarvisService on a background thread.
     */
    fun initialize() {
        Log.d(TAG, "Starting intensive component initialization...")
        val startTime = System.currentTimeMillis()

        // Room — memory layer
        db = JarvisDatabase.getInstance(context)
        val embeddingEngine = com.jarvis.assistant.memory.MemoryEmbeddingEngine.getInstance(context)
        memoryWriter = MemoryWriter(db.memoryDao(), db.conversationDao(), embeddingEngine)
        memoryReader = MemoryRetriever(db.memoryDao(), embeddingEngine)
        summarizer = MemorySummarizer(db.conversationDao(), llmRouter, memoryWriter)
        profileMemory = ProfileMemoryService(db.memoryFactDao())

        // Knowledge system
        val knowledgeRepo = KnowledgeRepository(
            db.knowledgeSourceDao(), db.wikiPageDao(), db.factRecordDao(),
            db.pageLinkDao(), db.knowledgeLogDao(), db.contradictionDao()
        )
        val knowledgeResolver = EntityResolver(knowledgeRepo)
        // completeSilent bypasses ConversationStore so entity extraction JSON blobs
        // never pollute the live conversation context the user sees on the next turn.
        knowledgeCompiler     = KnowledgeCompiler(knowledgeRepo, knowledgeResolver, llmRouter::completeSilent)
        knowledgeQuery        = KnowledgeQueryEngine(knowledgeRepo)
        retentionPolicy       = RetentionPolicy(knowledgeRepo)
        promptAssembler = PromptAssembler(contextEngine, memoryReader, profileMemory, knowledgeQuery)

        // Phase 7 — Orchestration
        reminderRepo = ReminderRepository(db.scheduledItemDao(), ReminderScheduler(context))

        // Referential-action store — powers "undo that" / "do the same for X".
        // In-memory only; lifetime matches the runtime.
        lastActionStore = com.jarvis.assistant.runtime.reference.LastActionStore()

        // Tool registry
        toolRegistry = ToolRegistry.buildDefault(
            context                = context,
            settings               = settings,
            memoryRetriever        = memoryReader,
            reminderRepository     = reminderRepo,
            outgoingCallController = outgoingCallController,
            locationProvider       = locationProvider,
            llmRouter              = llmRouter,
            lastActionStore        = lastActionStore
        )
        toolDispatcher = ToolDispatcher(context, toolRegistry, machine, lastActionStore)
        memoryHandler = MemoryActionHandler(profileMemory)
        reminderHandler = ReminderActionHandler(reminderRepo)
        planRunner = com.jarvis.assistant.runtime.plan.PlanRunner(
            context        = context,
            registry       = toolRegistry,
            journalDao     = db.actionJournalDao(),
            lastActionStore = lastActionStore
        )
        // Wire referential tools back to the runtime collaborators now that
        // both sides exist.  Safe to do post-construction because the tools
        // won't be invoked until the LLM decides to call them.
        toolRegistry.registeredNames.let {
            (toolRegistry.findByName("undo_last_action")
                as? com.jarvis.assistant.tools.reference.UndoLastActionTool)?.apply {
                registry = toolRegistry
                planRunner = this@JarvisRuntime.planRunner
            }
            (toolRegistry.findByName("repeat_last_action")
                as? com.jarvis.assistant.tools.reference.RepeatLastActionTool)?.apply {
                registry = toolRegistry
            }
        }

        // Phase 8 — Context-aware follow-ups
        followUpCoordinator = FollowUpCoordinator(
            context             = context,
            contactLookup       = ContactLookup(context),
            reminderRepository  = reminderRepo,
            settings            = settings
        )

        // Proactive awareness engine.  Quiet hours enabled in production so
        // nightly suggestions stay suppressed unless the event is critical
        // (low battery, imminent reminder); tests construct their own config.
        proactiveEngine = ProactiveEngine(
            config               = ProactiveConfig(
                quietHoursStartHour = 22,
                quietHoursEndHour   = 7
            ),
            reminderSource       = AppReminderSource(reminderRepo),
            callSource           = callSource,
            batterySource        = AppBatterySource(context, contextEngine),
            speechSource         = speechStateSource,
            notificationSource   = AppNotificationSource(),
            brainPredictionSource = AppBrainPredictionSource(
                brainEngineProvider  = { if (::brainEngine.isInitialized) brainEngine else null },
                knowledgeQueryEngine = knowledgeQuery
            ),
            calendarSource       = AppCalendarContextSource(context),
            locationSource       = com.jarvis.assistant.proactive.AppLocationContextSource(
                locationProvider = locationProvider,
                placeLearner     = com.jarvis.assistant.location.PlaceLearner(context)
            ),
            dispatcher           = TtsProactiveDispatcher(
                context              = context,
                ttsEngine            = ttsEngine,
                onPassiveAction      = { action -> Log.d(TAG, "Proactive passive: ${action.title}") },
                voiceResponseEnabled = { settings.voiceResponse }
            ),
            isDrivingProvider    = { drivingModeManager.isDriving }
        )

        // Conversational follow-up engine
        lastSeenTracker    = LastSeenTracker(context)
        followUpRepo       = FollowUpRepository(db.pendingFollowUpDao())
        convProactiveEngine = ConversationalProactiveEngine(
            followUpRepo = followUpRepo,
            lastSeen     = lastSeenTracker,
            onCheckIn    = ::dispatchConversationalCheckIn
        )

        // Phase 6 — Incoming call handling
        callCoordinator = CallCoordinator(
            ttsEngine     = ttsEngine,
            speechCapture = speechCapture,
            machine       = machine,
            resolver      = callResolver,
            executor      = callExecutor,
            syncState     = ::syncState,
            scope         = scope
        )

        // Speaker recognition
        speakerStore = SpeakerProfileStore(db.personRecordDao(), db.speakerEmbeddingDao(), db.recentGuestDao())
        speakerEnrollment = SpeakerEnrollmentManager(speakerStore)
        speakerCoordinator = SpeakerRecognitionCoordinator(speakerStore, speakerEnrollment)

        sessionId = memoryWriter.newSessionId()

        // Jarvis Brain — behavioural learning
        val brainDispatcher = TtsProactiveDispatcher(
            context              = context,
            ttsEngine            = ttsEngine,
            voiceResponseEnabled = { settings.voiceResponse }
        )
        brainEngine = com.jarvis.assistant.brain.BrainEngine(
            context       = context,
            eventDao      = db.brainEventDao(),
            patternDao    = db.brainPatternDao(),
            profileMemory = profileMemory,
            dispatcher    = brainDispatcher,
            speechSource  = speechStateSource,
            lastSeen      = lastSeenTracker
        )

        Log.d(TAG, "Intensive initialization complete in ${System.currentTimeMillis() - startTime}ms")
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun start() {
        bluetoothSco.start()          // Phase 4: register SCO receiver + headset proxy
        scope.launch(Dispatchers.IO) {
            anyoneRegistered = speakerStore.anyoneRegistered()
            anyoneEnrolled   = speakerStore.anyoneEnrolled()
        }
        scope.launch(Dispatchers.IO) { locationProvider.refresh() }

        // When a headset connects or disconnects while we're in wake-word mode,
        // restart the detector so it picks up (or drops) the SCO mic immediately.
        bluetoothSco.onHeadsetConnectionChanged = { connected ->
            if (machine.isIn<JarvisState.IdleWake>()) {
                Log.i(TAG, "BT headset ${if (connected) "connected" else "disconnected"} " +
                           "during wake-word mode — restarting detector")
                startWakeWordDetection()
            }
        }

        callMonitor.start()           // Phase 6: register telephony listener
        proactiveEngine.start()       // Proactive awareness polling
        convProactiveEngine.start()   // Conversational follow-up polling
        brainEngine.start()           // Behavioural learning system
        drivingModeManager.start(context)  // Driving mode detection
        followUpCoordinator.restorePersistedFlow()  // Restore any flow that survived a restart
        ttsEngine.applyVoice(settings.ttsVoiceName)
        machine.transition(JarvisState.IdleWake)
        syncState(JarvisState.IdleWake)
        startWakeWordDetection()
        startCallEventCollection()
        scope.launch(Dispatchers.IO) {
            memoryWriter.prune()
            reminderRepo.pruneDelivered()
        }

        // Drain any knowledge sources that were ingested in a previous session
        // but never finished compiling — e.g. app was killed mid-compile, or the
        // LLM call for entity extraction failed.  Without this, "remember X"
        // information persists as a raw source row but never becomes a wiki page
        // the retriever can surface, so the user perceives it as forgotten
        // across app restarts.
        scope.launch(Dispatchers.IO) {
            try {
                knowledgeCompiler.compilePending(batchSize = 5)
            } catch (e: Exception) {
                Log.w(TAG, "Startup compile-pending failed: ${e.message}")
            }
        }
    }

    /**
     * Called by [JarvisService] when a [ReminderAlarmReceiver] fires.
     * If the pipeline is idle, speak the reminder label immediately.
     * If busy (call active, speaking, etc.), post a system notification.
     */
    fun onReminderTriggered(reminderId: Long) {
        scope.launch {
            val item = withContext(Dispatchers.IO) { reminderRepo.getById(reminderId) }
                ?: run { Log.w(TAG, "Reminder id=$reminderId not found — ignoring"); return@launch }

            withContext(Dispatchers.IO) { reminderRepo.markDelivered(reminderId) }

            val isBusy = machine.current.isCallState ||
                         machine.isIn<JarvisState.Speaking>() ||
                         machine.isIn<JarvisState.Thinking>() ||
                         machine.isIn<JarvisState.ToolRunning>()

            if (isBusy) {
                Log.d(TAG, "Reminder fired while busy — posting notification id=$reminderId")
                val label = item.label
                JarvisNotificationHelper.postReminder(
                    context = context,
                    title   = "Reminder: $label",
                    body    = "Time for: $label"
                )
                return@launch
            }

            // Interrupt the wake-word listener briefly to speak the reminder
            wakeDetector?.stop()
            wakeDetector = null
            audioFocus.requestFocus()

            val word    = if (item.type.name == "TIMER") "Timer" else "Reminder"
            val message = "$word: ${item.label}."
            Log.d(TAG, "Speaking reminder: $message")
            machine.forceTransition(JarvisState.Speaking)
            syncState(JarvisState.Speaking)
            ttsEngine.speak(message)

            audioFocus.abandonFocus()
            backToWakeWord()
        }
    }

    /** Called by JarvisService when a geofence location reminder fires. */
    fun speakLocationReminder(label: String) {
        scope.launch {
            val isBusy = machine.current.isCallState ||
                         machine.isIn<JarvisState.Speaking>() ||
                         machine.isIn<JarvisState.Thinking>()
            if (isBusy) {
                JarvisNotificationHelper.postReminder(context, "Location reminder", label)
                return@launch
            }
            wakeDetector?.stop()
            wakeDetector = null
            audioFocus.requestFocus()
            machine.forceTransition(JarvisState.Speaking)
            syncState(JarvisState.Speaking)
            ttsEngine.speak("Location reminder: $label")
            audioFocus.abandonFocus()
            backToWakeWord()
        }
    }

    fun triggerManually() {
        wakeDetector?.stop()
        wakeDetector = null
        onWakeWordDetected()
    }

    /**
     * Apply a new TTS voice to the running engine.
     * Called when the user changes voice in Settings so the change takes effect
     * immediately without a service restart.
     */
    fun applyVoice(voiceName: String) {
        settings.ttsVoiceName = voiceName
        ttsEngine.applyVoice(voiceName)
        Log.d(TAG, "Voice applied: $voiceName")
    }

    /**
     * Switch to [voiceName] and speak a short test phrase so the user can audition it.
     * Suppresses and restores wake detection automatically.
     */
    fun testSpeak(voiceName: String) {
        applyVoice(voiceName)
        scope.launch {
            suppressWakeDetection()
            try {
                ttsEngine.speak("Hi, I'm Jarvis. This is how I sound.")
            } finally {
                restoreWakeDetection()
            }
        }
    }

    /**
     * Temporarily stop the wake-word detector.
     * Called by [JarvisService] when Settings is playing a TTS voice sample so
     * the sample audio doesn't accidentally trigger the pipeline.
     * Only acts when idle — does not interrupt an active conversation or call.
     */
    fun suppressWakeDetection() {
        if (!machine.isIn<JarvisState.IdleWake>()) {
            Log.d(TAG, "suppressWakeDetection ignored — not in IdleWake")
            return
        }
        Log.d(TAG, "Wake detection suppressed for sample playback")
        wakeWordSetupJob?.cancel()
        wakeWordSetupJob = null
        wakeDetector?.stop()
        wakeDetector = null
    }

    /**
     * Restart wake-word detection after a sample playback finishes.
     * Only acts when still idle so it doesn't stomp an active session.
     */
    fun restoreWakeDetection() {
        if (!machine.isIn<JarvisState.IdleWake>()) {
            Log.d(TAG, "restoreWakeDetection ignored — not in IdleWake")
            return
        }
        Log.d(TAG, "Wake detection restored after sample playback")
        startWakeWordDetection()
    }

    fun silence() {
        Log.d(TAG, "Silence requested")
        // Do not abort an in-progress call interaction
        if (machine.current.isCallState) {
            Log.d(TAG, "Silence ignored — runtime is in call state")
            return
        }
        closeSessionAsync()
        pipelineJob?.cancel()
        pipelineJob = null
        speechCapture.cancel()
        bargeIn.stop()
        ttsEngine.stopSpeaking()
        bluetoothSco.disconnect()  // Phase 4
        audioFocus.abandonFocus()  // Phase 5
        machine.transition(JarvisState.Silenced)
        machine.transition(JarvisState.IdleWake)
        syncState(JarvisState.IdleWake)
        startWakeWordDetection()
    }

    /**
     * Programmatically end an active outgoing call.
     * Called by [JarvisService] in response to [JarvisService.ACTION_END_CALL]
     * (notification action, overlay button, etc.).
     *
     * If no outgoing call is tracked, or the device does not support programmatic
     * end-call, the result is logged but no exception is thrown and no TTS is played
     * (this is a non-voice-pipeline path — the caller owns the user notification).
     */
    fun endActiveCall() {
        val result = outgoingCallController.endCall()
        Log.d(TAG, "endActiveCall() → $result")
        // The actual call state cleanup (state transition, backToWakeWord) happens
        // when TelephonyCallMonitor emits OutgoingCallEnded after the call drops.
        // No extra work needed here.
    }

    fun stop() {
        Log.d(TAG, "Stop requested")
        flushSessionToDb()  // synchronous — must complete before scope.cancel()
        pipelineJob?.cancel()
        pipelineJob = null
        callEventJob?.cancel()
        callEventJob = null
        wakeWordSetupJob?.cancel()
        wakeWordSetupJob = null
        activeCapture?.release()
        activeCapture = null
        wakeDetector?.stop()
        wakeDetector = null
        speechCapture.cancel()
        bargeIn.release()
        ttsEngine.stopSpeaking()
        ttsEngine.shutdown()
        // Release recording manager BEFORE bluetooth/focus teardown so the mic
        // is freed even if the user stops the service mid-recording.
        toolRegistry.release()
        bluetoothSco.release()   // Phase 4: full teardown
        audioFocus.abandonFocus() // Phase 5
        callMonitor.stop()              // Phase 6
        proactiveEngine.stop()          // Proactive awareness
        convProactiveEngine.stop()      // Conversational follow-up
        brainEngine.stop()              // Behavioural learning system
        drivingModeManager.stop(context) // Driving mode
        followUpCoordinator.clearActiveFlow()  // Phase 8
        machine.forceTransition(JarvisState.ServiceStopped)
        syncState(JarvisState.ServiceStopped)
        scope.cancel()
    }

    // ── Phase 6: Incoming call handling ──────────────────────────────────────

    /**
     * Subscribe to [callMonitor.events] for the lifetime of the runtime.
     * Collects on the Main dispatcher so it shares the same thread as the
     * rest of the pipeline.
     *
     * Handles:
     *   IncomingRinging      → delegate to CallCoordinator (existing)
     *   CallAnswered         → clear missed-call tracking (existing)
     *   CallEnded            → missed-call tracking (existing)
     *   OutgoingCallStarted  → suspend assistant (new)
     *   OutgoingCallEnded    → resume assistant (new)
     */
    private fun startCallEventCollection() {
        callEventJob?.cancel()
        callEventJob = scope.launch {
            callMonitor.events.collect { event ->
                when (event) {
                    is CallEvent.IncomingRinging -> {
                        pendingCallInfo = event
                        onIncomingCallEvent(event)
                    }
                    is CallEvent.CallAnswered    -> {
                        Log.d(TAG, "Call answered (off-hook)")
                        pendingCallInfo = null  // answered — not a missed call
                    }
                    is CallEvent.CallEnded       -> {
                        val missed = pendingCallInfo
                        pendingCallInfo = null
                        if (missed != null) {
                            // CallEnded without CallAnswered → missed call
                            val contactName = missed.callInfo.resolvedDisplayName
                                .takeUnless { it == "Unknown caller" }
                            callSource.recordMissedCall(System.currentTimeMillis(), contactName)
                            Log.d(TAG, "Missed call recorded: contact=$contactName")
                        } else {
                            Log.d(TAG, "Call ended (idle)")
                        }
                    }
                    is CallEvent.OutgoingCallStarted -> onOutgoingCallStarted(event)
                    is CallEvent.OutgoingCallEnded   -> onOutgoingCallEnded(event)
                    else                             -> {}
                }
            }
        }
    }

    /**
     * An outgoing call has been placed (CALL_STATE_OFFHOOK without prior RINGING).
     *
     * The phone dialer now owns the microphone.  Jarvis must:
     *   1. Cancel any in-progress pipeline (TTS, listening, LLM, tools)
     *   2. Stop the wake-word detector
     *   3. Abandon audio focus
     *   4. Transition to [JarvisState.OutgoingCallActive] and wait
     *
     * Recovery happens in [onOutgoingCallEnded] when CALL_STATE_IDLE fires.
     */
    private fun onOutgoingCallStarted(event: CallEvent.OutgoingCallStarted) {
        Log.i(TAG, "Outgoing call detected — suspending assistant")
        outgoingCallController.notifyCallStarted()

        // Abort whatever the pipeline was doing
        pipelineJob?.cancel()
        pipelineJob = null
        ttsEngine.stopSpeaking()
        bargeIn.stop()
        speechCapture.cancel()
        wakeDetector?.stop()
        wakeDetector = null
        closeSessionAsync()

        // Release audio focus — phone call will take STREAM_VOICE_CALL
        audioFocus.abandonFocus()

        machine.forceTransition(JarvisState.OutgoingCallActive)
        syncState(JarvisState.OutgoingCallActive)

        Log.d(TAG, "Assistant suspended in OutgoingCallActive")
    }

    /**
     * The outgoing call has ended (CALL_STATE_IDLE after an outgoing OFFHOOK).
     *
     * Clean up call tracking and return Jarvis to normal wake-word listening.
     * Uses the same [CallRecovery] → [IdleWake] path as incoming call cleanup.
     */
    private fun onOutgoingCallEnded(event: CallEvent.OutgoingCallEnded) {
        Log.i(TAG, "Outgoing call ended — resuming assistant")
        outgoingCallController.notifyCallEnded()

        // Use validated transition: OutgoingCallActive → CallRecovery → IdleWake.
        // If for some reason we're not in OutgoingCallActive (e.g. rapid state
        // changes), forceTransition ensures we always reach a known state.
        if (!machine.transition(JarvisState.CallRecovery)) {
            Log.w(TAG, "Unexpected state ${machine.current::class.simpleName} on outgoing call end — forcing recovery")
            machine.forceTransition(JarvisState.CallRecovery)
        }
        syncState(JarvisState.CallRecovery)
        backToWakeWord()
    }

    /**
     * An incoming ringing call has been detected.
     *
     * 1. Cancel whatever the pipeline was doing (conversation, TTS, listening).
     * 2. Stop the wake-word detector so it doesn't interfere.
     * 3. Request audio focus for the call announcement.
     * 4. Delegate to [CallCoordinator].
     * 5. When the coordinator finishes, return to wake-word mode.
     */
    private fun onIncomingCallEvent(event: CallEvent.IncomingRinging) {
        Log.d(TAG, "Incoming call — interrupting pipeline")

        // Abort whatever was running
        pipelineJob?.cancel()
        pipelineJob = null
        ttsEngine.stopSpeaking()
        bargeIn.stop()
        speechCapture.cancel()
        wakeDetector?.stop()
        wakeDetector = null
        closeSessionAsync()

        // Update device state so the UI can show call info
        DeviceStateStore.update { copy(incomingCallInfo = event.callInfo) }

        pipelineJob = scope.launch {
            try {
                audioFocus.requestFocus()   // need focus to speak the announcement
                callCoordinator.handleIncomingCall(event, callMonitor.events)
            } catch (e: CancellationException) {
                throw e   // propagate — service is stopping
            } catch (e: Exception) {
                Log.e(TAG, "Call coordinator error: ${e.message}", e)
            } finally {
                DeviceStateStore.update { copy(incomingCallInfo = null, activeCallInfo = null) }
                audioFocus.abandonFocus()
                backToWakeWord()
            }
        }
    }

    // ── Wake-word detection ───────────────────────────────────────────────────

    /**
     * Start the wake-word detection loop.
     *
     * If a Bluetooth headset is connected, we activate SCO first so the
     * SpeechRecognizer captures audio from the headset mic (which is right next
     * to the user's mouth) rather than the phone's distant built-in mic.
     * SCO connect is async (up to 4 s); we launch it as a coroutine so the
     * caller is never blocked.  A new [wakeWordSetupJob] is created on every
     * call — any previous in-flight setup is cancelled first.
     */
    private fun startWakeWordDetection() {
        wakeDetector?.stop()
        wakeDetector = null
        wakeWordSetupJob?.cancel()
        wakeWordSetupJob = scope.launch {
            if (bluetoothSco.isHeadsetConnected) {
                Log.d(TAG, "BT headset connected — activating SCO before wake-word detector")
                // Timeout guard: if SCO negotiation hangs past 5 s (broken
                // Bluetooth stack), skip it and fall back to the built-in mic
                // so the wake-word detector still starts instead of freezing.
                val ok = withTimeoutOrNull(5_000L) { bluetoothSco.connect() } ?: false
                Log.d(TAG, "SCO for wake-word: active=$ok")
            }
            // Bail if the runtime was stopped while we were waiting for SCO.
            if (!scope.isActive) return@launch
            // Prefer offline TFLite detector when model asset is present; fall back to Google STT.
            val tflite = TFLiteWakeWordDetector(context, ::onWakeWordDetected) { err ->
                Log.w(TAG, "TFLite wake-word: $err — falling back to Google STT")
            }
            wakeDetector = if (tflite.isAvailable) tflite
            else GoogleWakeWordDetector(
                context    = context,
                onDetected = ::onWakeWordDetected,
                onError    = { Log.w(TAG, "WakeWordDetector error — will self-heal") }
            )
            wakeDetector!!.start()
        }
    }

    // ── Pipeline ──────────────────────────────────────────────────────────────

    private fun onWakeWordDetected() {
        pipelineJob?.cancel()
        LatencyTracker.pipelineStart()

        pipelineJob = scope.launch {
            try {
                LatencyTracker.mark("WAKE_DETECTED")
                // Open a new memory session for this activation.
                // Also clear follow-up entity context so pronouns from a previous
                // session don't bleed into a new one.
                sessionId   = memoryWriter.newSessionId()
                sessionOpen = true
                memoryWriter.openSession(sessionId)
                followUpCoordinator.entityTracker.clear()

                machine.transitionAnd(JarvisState.WakeDetected) { syncState(JarvisState.WakeDetected) }

                wakeDetector?.stop()
                wakeDetector = null

                // Android's SpeechRecognizer.destroy() is asynchronous — the
                // speech service releases the microphone in a separate process.
                // Without a sufficient pause here, SpeechCapture.listen() creates a
                // new SpeechRecognizer before the old one's mic session is gone,
                // and the new session silently captures nothing.
                // Bumped from 350ms to 800ms for hardware stability.
                delay(800)

                // Phase 5: request audio focus before doing anything with audio
                audioFocus.requestFocus()

                // Phase 4: connect SCO if a headset is present
                val scoConnected = bluetoothSco.connect()
                if (scoConnected) {
                    Log.d(TAG, "SCO active — routing audio through headset")
                    DeviceStateStore.update { copy(headsetConnected = true) }
                }

                // Reset speaker identity and prepare the capture object.
                // SpeakerAudioCapture uses a concurrent VOICE_RECOGNITION AudioRecord
                // session (API 29+). On some devices the concurrent capture degrades
                // SpeechRecognizer output to silence. Disabled until a per-device
                // compatibility check or an alternative PCM source is available.
                sessionSpeaker = SpeakerSessionContext()
                activeCapture?.release()
                activeCapture = null

                ttsEngine.playChime()

                // Load neural speaker encoder once (no-op on subsequent sessions).
                SpeakerEmbeddingEngine.init(context)

                // First-run onboarding: no owner name recorded yet — prompt for setup.
                // Re-read from DB to avoid a race with the async load in start().
                if (!anyoneRegistered) {
                    anyoneRegistered = withContext(Dispatchers.IO) { speakerStore.anyoneRegistered() }
                    anyoneEnrolled   = withContext(Dispatchers.IO) { speakerStore.anyoneEnrolled() }
                }
                if (!anyoneRegistered) {
                    speakAndRecord("Hi! I'm Jarvis. I haven't been set up yet — what's your name?")
                    sessionSpeaker = sessionSpeaker.copy(awaitingOwnerName = true)
                }

                // Settings-triggered enrollment: user tapped "Train Voice" in the Settings UI.
                val pendingEnrollPid = settings.pendingVoiceEnrollmentPersonId
                if (pendingEnrollPid >= 0L && !sessionSpeaker.awaitingOwnerName) {
                    settings.pendingVoiceEnrollmentPersonId = -1L
                    val pendingPerson = withContext(Dispatchers.IO) { speakerStore.getPersonById(pendingEnrollPid) }
                    if (pendingPerson != null) {
                        sessionSpeaker = SpeakerSessionContext(
                            result = SpeakerIdentityResult(
                                confidence  = 1f,
                                personId    = pendingPerson.id,
                                displayName = pendingPerson.displayName,
                                band        = SpeakerIdentityResult.ConfidenceBand.HIGH_CONFIDENCE_MATCH
                            ),
                            awaitingVoiceEnrollmentSample   = true,
                            voiceEnrollmentSamplesRemaining = SAMPLES_TO_COLLECT
                        )
                        val firstName = pendingPerson.displayName.substringBefore(' ')
                        speakAndRecord(
                            "Hi $firstName! Starting voice enrollment. " +
                            "Say: \"${ENROLLMENT_PROMPTS.first()}\""
                        )
                        machine.transitionAnd(JarvisState.Listening) { syncState(JarvisState.Listening) }
                    }
                }

                machine.transitionAnd(JarvisState.Listening) { syncState(JarvisState.Listening) }

                var consecutiveFastFails = 0

                // ── Conversation loop ──────────────────────────────────────────
                while (true) {
                    if (!machine.isIn<JarvisState.Listening>() &&
                        !machine.isIn<JarvisState.Interrupted>()) break

                    syncState(machine.current)

                    // SpeakerAudioCapture (for speaker ID) is started via the onReady 
                    // callback. To avoid hardware contention, we use a 100ms 
                    // additional stagger after onReadyForSpeech to ensure the 
                    // system's recognition service has fully stabilized its 
                    // own AudioRecord before we open ours.
                    val listenStart = System.currentTimeMillis()
                    val transcript  = speechCapture.listen(onReady = {
                        scope.launch {
                            delay(100)
                            activeCapture?.start()
                        }
                    })
                    // Stop capture immediately — PCM is for this utterance only.
                    val utterancePcm = activeCapture?.stop()
                    val elapsed     = System.currentTimeMillis() - listenStart

                    if (transcript.isBlank()) {
                        if (elapsed < FAST_FAIL_THRESHOLD) {
                            consecutiveFastFails++
                            if (consecutiveFastFails >= MAX_FAST_FAILS) {
                                machine.transition(JarvisState.MicUnavailable)
                                syncState(JarvisState.MicUnavailable)
                                closeSessionAsync()
                                releaseResources()
                                backToWakeWord()
                                return@launch
                            }
                            delay(1_000)
                        } else {
                            consecutiveFastFails = 0
                            delay(600)
                        }
                        machine.transition(JarvisState.Listening)
                        syncState(JarvisState.Listening)
                        continue
                    }

                    consecutiveFastFails = 0
                    LatencyTracker.mark("STT_COMPLETE")
                    speechStateSource.recordUserInteraction()
                    lastSeenTracker.touchUserTurn()  // track presence for gap check-ins
                    DeviceStateStore.update { copy(lastUserUtterance = transcript) }
                    val implicitMemoryStored = memoryWriter.writeTurn(sessionId, "user", transcript)
                    brainEngine.collector.onUserMessage(transcript)
                    // Resolve any pending follow-ups whose topic comes up naturally
                    scope.launch(Dispatchers.IO) { followUpRepo.maybeResolveFromTranscript(transcript) }

                    // ── Plan confirmation / undo intercept ─────────────────────
                    // A pending plan is one Jarvis just proposed ("Three things —
                    // …. Go?").  Short go/cancel utterances dispatch directly to
                    // the runner without going near the LLM.  "Undo" is also
                    // intercepted regardless of pending state so the rollback
                    // path is always available.
                    val pending = pendingPlan
                    val trimmed = transcript.trim()
                    if (pending != null && trimmed.split(Regex("\\s+")).size <= 4) {
                        when {
                            planConfirmRegex.matches(trimmed) -> {
                                pendingPlan = null
                                val res = planRunner.execute(pending)
                                val spoken = when (res) {
                                    is com.jarvis.assistant.runtime.plan.PlanRunner.Resolution.Ran -> res.spoken
                                    is com.jarvis.assistant.runtime.plan.PlanRunner.Resolution.Halted -> res.spoken
                                    is com.jarvis.assistant.runtime.plan.PlanRunner.Resolution.Cancelled -> res.spoken
                                }
                                speakAndRecord(spoken)
                                machine.transition(JarvisState.Listening)
                                syncState(JarvisState.Listening)
                                continue
                            }
                            planCancelRegex.matches(trimmed) -> {
                                val res = planRunner.cancel(pending)
                                pendingPlan = null
                                speakAndRecord(res.spoken)
                                machine.transition(JarvisState.Listening)
                                syncState(JarvisState.Listening)
                                continue
                            }
                        }
                    }
                    if (planUndoRegex.matches(trimmed)) {
                        // Drop any pending unconfirmed plan — undoing while
                        // still confirming is a clear cancel signal.
                        pendingPlan = null
                        val res = planRunner.undoLastPlan()
                        val spoken = when (res) {
                            is com.jarvis.assistant.runtime.plan.PlanRunner.UndoResult.Done -> res.spoken
                            is com.jarvis.assistant.runtime.plan.PlanRunner.UndoResult.Nothing -> "Nothing to undo."
                            is com.jarvis.assistant.runtime.plan.PlanRunner.UndoResult.TooOld -> "That's too old to undo cleanly."
                        }
                        speakAndRecord(spoken)
                        machine.transition(JarvisState.Listening)
                        syncState(JarvisState.Listening)
                        continue
                    }

                    // ── Interruption handling ──────────────────────────────────
                    // If the last turn was cut short by barge-in, classify this new
                    // utterance against what Jarvis had been saying and decide:
                    //   CONTINUE / CLARIFICATION → optionally resume afterwards
                    //   CORRECTION / REPLACEMENT / URGENT / UNRELATED → discard
                    val resumable = lastInterrupted?.takeUnless { it.isStale() }
                    if (resumable != null) {
                        // Don't consume yet — CLARIFICATION keeps it alive for a follow-up
                        // "go on" from the user.  Only clear once we know what to do with it.
                        val interruption = InterruptionClassifier.classify(
                            utterance    = transcript,
                            spokenTokens = resumable.spokenTokens
                        )
                        Log.d(TAG, "Interrupt classified as $interruption " +
                                    "(spoken='${resumable.spokenSoFar.take(40)}')")

                        when (interruption) {
                            InterruptionType.URGENT -> {
                                lastInterrupted = null
                                speakAndRecord("Okay.")
                                machine.transition(JarvisState.Listening)
                                syncState(JarvisState.Listening)
                                continue
                            }
                            InterruptionType.CONTINUE -> {
                                lastInterrupted = null
                                if (resumable.resumable && resumable.spokenSoFar.isNotBlank()) {
                                    // Re-invoke the LLM to continue naturally — never replay
                                    // words that were already spoken.  The model is told what
                                    // it said so far and asked to pick up with a short
                                    // connector ("Right, so…", "Yeah,") and no repetition.
                                    resumeContinuation(resumable, contextEngine.isOnline())
                                }
                                machine.transition(JarvisState.Listening)
                                syncState(JarvisState.Listening)
                                continue
                            }
                            InterruptionType.CLARIFICATION -> {
                                // Keep lastInterrupted alive — user may say "go on" after
                                // their clarifying question is answered.
                            }
                            InterruptionType.CORRECTION,
                            InterruptionType.REPLACEMENT,
                            InterruptionType.UNRELATED -> {
                                lastInterrupted = null
                            }
                        }
                    }

                    // ── Speaker recognition ────────────────────────────────────
                    // Text-based flows run regardless of PCM availability.
                    // PCM is used opportunistically for voice enrollment where present.
                    when {
                        sessionSpeaker.awaitingOwnerName -> {
                            // First-run onboarding step 1: parse the owner's name.
                            val name = speakerCoordinator.parseIntroductionName(transcript)
                            if (name != null) {
                                val person = withContext(Dispatchers.IO) {
                                    speakerCoordinator.createPersonFromIntroduction(
                                        name, utterancePcm, isOwner = true
                                    )
                                }
                                anyoneRegistered = true
                                sessionSpeaker = sessionSpeaker.copy(
                                    awaitingOwnerName       = false,
                                    awaitingOwnerVoiceSample = true,
                                    pendingOwnerName        = person.displayName
                                )
                                speakAndRecord(
                                    "Hi ${person.displayName}! Now say a sentence so I can " +
                                    "learn your voice — for example: hey Jarvis, set a timer for five minutes."
                                )
                                machine.transition(JarvisState.Listening)
                                syncState(JarvisState.Listening)
                                continue
                            }
                            // Couldn't parse a name — ask again
                            speakAndRecord("Sorry, I didn't catch that. What's your name?")
                            machine.transition(JarvisState.Listening)
                            syncState(JarvisState.Listening)
                            continue
                        }
                        sessionSpeaker.awaitingOwnerVoiceSample -> {
                            // First-run onboarding step 2: collect voice sample for recognition.
                            val ownerName = sessionSpeaker.pendingOwnerName ?: "there"
                            if (utterancePcm != null) {
                                val owner = withContext(Dispatchers.IO) { speakerStore.getOwner() }
                                if (owner != null) {
                                    // Enroll synchronously so anyoneEnrolled reflects reality
                                    // before we grant HIGH_CONFIDENCE below.
                                    withContext(Dispatchers.IO) {
                                        speakerCoordinator.enrollUtterance(owner.id, utterancePcm)
                                    }
                                }
                            } else {
                                // PCM unavailable — voice profile will be empty.  The owner
                                // is still onboarded by name but recognition won't work until
                                // they accumulate samples in subsequent sessions.
                                Log.w(TAG, "Owner onboarding: no PCM available — voice profile not seeded")
                            }
                            val owner         = withContext(Dispatchers.IO) { speakerStore.getOwner() }
                            val alreadyHave   = if (utterancePcm != null) 1 else 0
                            val remaining     = SAMPLES_TO_COLLECT - alreadyHave
                            val firstPromptIdx = alreadyHave.coerceIn(0, ENROLLMENT_PROMPTS.lastIndex)
                            sessionSpeaker = SpeakerSessionContext(
                                result = SpeakerIdentityResult(
                                    confidence  = 1f,
                                    personId    = owner?.id,
                                    displayName = owner?.displayName ?: ownerName,
                                    band        = SpeakerIdentityResult.ConfidenceBand.HIGH_CONFIDENCE_MATCH
                                ),
                                awaitingVoiceEnrollmentSample   = remaining > 0,
                                voiceEnrollmentSamplesRemaining = remaining
                            )
                            promptAssembler.invalidateProfileCache()
                            if (remaining > 0) {
                                speakAndRecord(
                                    "Got it, $ownerName! A few more voice samples will help me recognise " +
                                    "you straight away. Say: \"${ENROLLMENT_PROMPTS[firstPromptIdx]}\""
                                )
                            } else {
                                speakAndRecord("Got it, $ownerName! How can I help?")
                            }
                            machine.transition(JarvisState.Listening)
                            syncState(JarvisState.Listening)
                            continue
                        }
                        sessionSpeaker.awaitingIntroductionReply -> {
                            val name = speakerCoordinator.parseIntroductionName(transcript)
                            if (name != null) {
                                val captured = sessionSpeaker.pendingPcm
                                // Check if a stored profile already carries this name — if so,
                                // link the session to it at LOW_CONFIDENCE rather than treating
                                // the person as a brand-new guest (fixes cross-session re-intro).
                                val existingPerson = withContext(Dispatchers.IO) {
                                    speakerStore.getPersonByName(name)
                                }
                                if (existingPerson != null) {
                                    sessionSpeaker = SpeakerSessionContext(
                                        result = SpeakerIdentityResult(
                                            confidence  = 0.5f,
                                            personId    = existingPerson.id,
                                            displayName = existingPerson.displayName,
                                            band        = SpeakerIdentityResult.ConfidenceBand.LOW_CONFIDENCE_OR_AMBIGUOUS
                                        ),
                                        pendingPcm = captured
                                    )
                                    speakAndRecord(
                                        "Welcome back, ${existingPerson.displayName}! " +
                                        "I've linked this session to your profile. How can I help?"
                                    )
                                } else {
                                    // New person — record for cross-session memory and set up guest session.
                                    scope.launch(Dispatchers.IO) { speakerStore.recordRecentGuest(name) }
                                    sessionSpeaker = SpeakerSessionContext(
                                        result = SpeakerIdentityResult(
                                            confidence  = 0.5f,
                                            personId    = null,
                                            displayName = name,
                                            band        = SpeakerIdentityResult.ConfidenceBand.LOW_CONFIDENCE_OR_AMBIGUOUS
                                        ),
                                        pendingPcm = captured  // preserved for enrollment seeding on "remember me"
                                    )
                                    speakAndRecord(
                                        "Hi $name! I'll use your name for this conversation. " +
                                        "Say 'remember me' if you'd like me to recognise you next time."
                                    )
                                }
                                machine.transition(JarvisState.Listening)
                                syncState(JarvisState.Listening)
                                continue
                            }
                            // Name parse failed — clear flag and let LLM handle it
                            sessionSpeaker = sessionSpeaker.copy(awaitingIntroductionReply = false)
                        }
                        sessionSpeaker.awaitingGuestEnrollmentSample -> {
                            // Guest said "remember me" — create profile, enroll this utterance
                            // plus the original unknown utterance (pendingPcm) if available.
                            val name          = sessionSpeaker.result.displayName ?: "you"
                            val introductionPcm = sessionSpeaker.pendingPcm
                            val person = withContext(Dispatchers.IO) {
                                speakerCoordinator.createPersonFromIntroduction(name, utterancePcm)
                            }
                            if (introductionPcm != null) {
                                withContext(Dispatchers.IO) {
                                    speakerCoordinator.enrollUtterance(person.id, introductionPcm)
                                }
                            }
                            anyoneRegistered = true
                            anyoneEnrolled   = true
                            // Chain directly into multi-sample enrollment so the profile reaches
                            // SUFFICIENT status in the same session rather than stopping at 1–2 samples.
                            val alreadyEnrolled = if (introductionPcm != null) 2 else 1
                            val remaining       = SAMPLES_TO_COLLECT - alreadyEnrolled
                            val firstPromptIdx  = SAMPLES_TO_COLLECT - remaining
                            sessionSpeaker = SpeakerSessionContext(
                                result = SpeakerIdentityResult(
                                    confidence  = 1f,
                                    personId    = person.id,
                                    displayName = person.displayName,
                                    band        = SpeakerIdentityResult.ConfidenceBand.HIGH_CONFIDENCE_MATCH
                                ),
                                awaitingVoiceEnrollmentSample   = remaining > 0,
                                voiceEnrollmentSamplesRemaining = remaining
                            )
                            if (remaining > 0) {
                                speakAndRecord(
                                    "Profile saved, ${person.displayName}! A few more samples will help me " +
                                    "recognise you reliably. Say: \"${ENROLLMENT_PROMPTS[firstPromptIdx]}\""
                                )
                            } else {
                                speakAndRecord("Done, ${person.displayName}! I'll recognise you next time. How can I help?")
                            }
                            machine.transition(JarvisState.Listening)
                            syncState(JarvisState.Listening)
                            continue
                        }
                        sessionSpeaker.awaitingVoiceEnrollmentSample -> {
                            val pid = sessionSpeaker.result.personId
                            if (pid != null && utterancePcm != null) {
                                withContext(Dispatchers.IO) {
                                    speakerCoordinator.enrollUtterance(pid, utterancePcm)
                                }
                            } else if (utterancePcm == null) {
                                Log.w(TAG, "Voice enrollment: no PCM captured for sample, skipping")
                            }
                            val remaining = sessionSpeaker.voiceEnrollmentSamplesRemaining - 1
                            if (remaining > 0) {
                                val nextPrompt = ENROLLMENT_PROMPTS[SAMPLES_TO_COLLECT - remaining]
                                sessionSpeaker = sessionSpeaker.copy(voiceEnrollmentSamplesRemaining = remaining)
                                speakAndRecord("Good. $remaining more — please say: \"$nextPrompt\"")
                            } else {
                                anyoneEnrolled = true
                                val name = sessionSpeaker.result.displayName?.substringBefore(' ') ?: "there"
                                sessionSpeaker = sessionSpeaker.copy(
                                    awaitingVoiceEnrollmentSample   = false,
                                    voiceEnrollmentSamplesRemaining = 0
                                )
                                speakAndRecord(
                                    "All done, $name! I've collected $SAMPLES_TO_COLLECT voice samples " +
                                    "and will recognise you much better now. How can I help?"
                                )
                            }
                            machine.transition(JarvisState.Listening)
                            syncState(JarvisState.Listening)
                            continue
                        }
                        utterancePcm != null && !sessionSpeaker.isKnown -> {
                            // First turn with PCM available — attempt speaker identification.
                            val identResult = withContext(Dispatchers.IO) {
                                speakerCoordinator.identify(utterancePcm)
                            }
                            sessionSpeaker = sessionSpeaker.copy(result = identResult)

                            when (identResult.band) {
                                SpeakerIdentityResult.ConfidenceBand.HIGH_CONFIDENCE_MATCH -> {
                                    identResult.personId?.let { pid ->
                                        scope.launch(Dispatchers.IO) {
                                            // Only enroll at SILENT_ENROLL_THRESHOLD (above THRESHOLD_HIGH)
                                            // to prevent profile drift from borderline false-positives.
                                            if (identResult.confidence >= SpeakerEmbeddingEngine.SILENT_ENROLL_THRESHOLD) {
                                                speakerCoordinator.enrollUtterance(pid, utterancePcm)
                                            }
                                            speakerCoordinator.recordInteraction(pid)
                                        }
                                    }
                                }
                                SpeakerIdentityResult.ConfidenceBand.LOW_CONFIDENCE_OR_AMBIGUOUS,
                                SpeakerIdentityResult.ConfidenceBand.UNKNOWN -> {
                                    if (anyoneEnrolled) {
                                        // Surface recent guests to avoid a cold "who's this?" every time.
                                        val recentGuests = withContext(Dispatchers.IO) {
                                            speakerStore.getRecentGuests()
                                        }
                                        val prompt = if (recentGuests.isNotEmpty()) {
                                            val names = recentGuests.take(2).joinToString(" or ") { it.displayName }
                                            "Hi! I don't recognise your voice. Are you $names, or someone new?"
                                        } else {
                                            "Hi, I don't recognise your voice. Who's this?"
                                        }
                                        sessionSpeaker = sessionSpeaker.copy(
                                            askedForIntroduction      = true,
                                            awaitingIntroductionReply = true,
                                            pendingPcm                = utterancePcm
                                        )
                                        speakAndRecord(prompt)
                                        machine.transition(JarvisState.Listening)
                                        syncState(JarvisState.Listening)
                                        continue
                                    }
                                }
                            }
                        }
                        utterancePcm != null -> {
                            // Ongoing session — only silently enroll for confirmed HIGH_CONFIDENCE
                            // sessions to prevent drifting a profile with unverified utterances.
                            if (sessionSpeaker.isHighConfidence) {
                                sessionSpeaker.result.personId?.let { pid ->
                                    scope.launch(Dispatchers.IO) {
                                        speakerCoordinator.enrollUtterance(pid, utterancePcm)
                                    }
                                }
                            }
                        }
                    }

                    // ── "Remember me" request ──────────────────────────────────
                    // Guest has a name in-session (personId == null) and wants to be stored.
                    if (REMEMBER_ME_PATTERN.containsMatchIn(transcript) &&
                            sessionSpeaker.result.displayName != null &&
                            sessionSpeaker.result.personId == null) {
                        sessionSpeaker = sessionSpeaker.copy(awaitingGuestEnrollmentSample = true)
                        speakAndRecord(
                            "Sure! Say a sentence so I can learn your voice — " +
                            "for example: hey Jarvis, what's the weather today?"
                        )
                        machine.transition(JarvisState.Listening)
                        syncState(JarvisState.Listening)
                        continue
                    }

                    // ── "Train my voice" / manual enrollment request ──────────────
                    if (ENROLL_VOICE_PATTERN.containsMatchIn(transcript)) {
                        var enrollPersonId = sessionSpeaker.result.personId
                        var enrollName     = sessionSpeaker.result.displayName
                        when {
                            enrollPersonId != null -> {
                                // Known speaker with a stored profile — enroll directly.
                            }
                            enrollName != null -> {
                                // Guest introduced by name this session but not yet stored.
                                // Enrolling into the owner profile would be wrong here.
                                speakAndRecord(
                                    "I know your name, $enrollName, but I don't have a stored " +
                                    "voice profile for you yet. Say 'remember me' first and then " +
                                    "I can train your voice."
                                )
                                machine.transition(JarvisState.Listening)
                                syncState(JarvisState.Listening)
                                continue
                            }
                            else -> {
                                // Owner trust mode — no one enrolled yet, fall back to owner record.
                                val owner = withContext(Dispatchers.IO) { speakerStore.getOwner() }
                                enrollPersonId = owner?.id
                                enrollName     = owner?.displayName
                            }
                        }
                        if (enrollPersonId != null) {
                            sessionSpeaker = sessionSpeaker.copy(
                                awaitingVoiceEnrollmentSample   = true,
                                voiceEnrollmentSamplesRemaining = SAMPLES_TO_COLLECT,
                                result = SpeakerIdentityResult(
                                    confidence  = 1f,
                                    personId    = enrollPersonId,
                                    displayName = enrollName,
                                    band        = SpeakerIdentityResult.ConfidenceBand.HIGH_CONFIDENCE_MATCH
                                )
                            )
                            val firstName = enrollName?.substringBefore(' ') ?: "there"
                            speakAndRecord(
                                "Sure, $firstName! I'll collect $SAMPLES_TO_COLLECT voice samples. " +
                                "Please say: \"${ENROLLMENT_PROMPTS.first()}\""
                            )
                        } else {
                            speakAndRecord(
                                "I don't have a profile to link your voice to. " +
                                "Tell me your name first and I'll set one up."
                            )
                        }
                        machine.transition(JarvisState.Listening)
                        syncState(JarvisState.Listening)
                        continue
                    }

                    // ── Owner trust mode ──────────────────────────────────────────
                    // When no voice profiles have been enrolled the biometric gate is
                    // not in play.  Blocking personal actions (calls, messages, etc.)
                    // in this state makes the app unusable.  Synthesise a HIGH_CONFIDENCE
                    // result so SpeakerPermissionPolicy allows owner actions.
                    // This is safe because with zero enrollments there is no voice
                    // verification to bypass — it simply hasn't been set up yet.
                    // Once at least one profile IS enrolled, normal biometric gating
                    // resumes for unrecognised voices.
                    if (!anyoneEnrolled && !sessionSpeaker.isKnown) {
                        Log.d(TAG, "No voice profiles enrolled — applying owner trust mode")
                        sessionSpeaker = sessionSpeaker.copy(
                            result = SpeakerIdentityResult(
                                confidence  = 1f,
                                personId    = null,
                                displayName = null,
                                band        = SpeakerIdentityResult.ConfidenceBand.HIGH_CONFIDENCE_MATCH
                            )
                        )
                    }

                    // Advisory transcript safety check — logs unsafe inputs, does not block LLM
                    ActionPolicyGate.validateTranscript(transcript)?.let { bad ->
                        Log.w(TAG, "Transcript policy flag: ${bad::class.simpleName}")
                    }

                    // Stop command → exit conversation
                    if (isStopCommand(transcript)) {
                        // If a recording is active, stop it before going idle so
                        // the MediaRecorder releases the microphone.
                        toolRegistry.release()
                        val bye = "Okay, going quiet. Say Jarvis to wake me."
                        speakAndRecord(bye)
                        closeSessionAsync()
                        releaseResources()
                        backToWakeWord()
                        return@launch
                    }

                    // ── Phase 8: Context-aware follow-up system ────────────────
                    // Runs before IntentClassifier so active multi-turn flows
                    // take priority over single-shot pattern matching.
                    val flowResult = withContext(Dispatchers.IO) {
                        followUpCoordinator.process(transcript)
                    }
                    LatencyTracker.mark("FLOW_CHECKED")
                    when (flowResult) {
                        is FlowResult.AwaitingInput -> {
                            speakAndRecord(flowResult.prompt)
                            machine.transition(JarvisState.Listening)
                            syncState(JarvisState.Listening)
                            continue
                        }
                        is FlowResult.Complete -> {
                            speakAndRecord(flowResult.response)
                            machine.transition(JarvisState.Listening)
                            syncState(JarvisState.Listening)
                            continue
                        }
                        is FlowResult.Cancelled -> {
                            speakAndRecord(flowResult.message)
                            machine.transition(JarvisState.Listening)
                            syncState(JarvisState.Listening)
                            continue
                        }
                        FlowResult.PassThrough -> { /* fall through */ }
                    }

                    // ── Intent classifier (memory / reminder pre-filter) ───────
                    machine.transition(JarvisState.Thinking)
                    syncState(JarvisState.Thinking)

                    val action = IntentClassifier.classify(transcript)
                    LatencyTracker.mark("INTENT_CLASSIFIED")
                    if (action !is ConversationAction.PassThrough) {
                        val response = withContext(Dispatchers.IO) {
                            when (action) {
                                is ConversationAction.RememberFact   -> memoryHandler.handleStore(action)
                                    .also { promptAssembler.invalidateProfileCache() }
                                is ConversationAction.RecallFact     -> memoryHandler.handleRecall(action)
                                is ConversationAction.CreateReminder -> reminderHandler.handleCreate(action)
                                is ConversationAction.CreateTimer    -> reminderHandler.handleTimer(action)
                                ConversationAction.ListReminders     -> reminderHandler.handleList(ConversationAction.ListReminders)
                                is ConversationAction.CancelReminder -> reminderHandler.handleCancel(action)
                                else -> null
                            }
                        }
                        if (response != null) {
                            speakAndRecord(response)
                            machine.transition(JarvisState.Listening)
                            syncState(JarvisState.Listening)
                            continue
                        }
                    }

                    // ── Conversation intent classification ─────────────────────
                    // Classify BEFORE tool matching so casual/personal messages
                    // never reach the tool layer (and never trigger web search).
                    val convIntent = ConversationClassifier.classify(transcript)
                    LatencyTracker.mark("INTENT_CLASSIFIED_CONV")

                    // ── Tool dispatch ──────────────────────────────────────────
                    val isOnline = contextEngine.isOnline()
                    // Gate: PERSONAL_UPDATE and CASUAL_CHAT skip tools entirely.
                    val matched = if (ToolUsePolicy.allowsTools(convIntent)) {
                        toolRegistry.match(transcript, isOnline)
                    } else {
                        null
                    }
                    LatencyTracker.mark("TOOL_MATCHED")

                    if (matched != null) {
                        val (tool, input) = matched
                        syncState(machine.current)

                        val dispatchResult = toolDispatcher.dispatch(tool, input, sessionSpeaker, transcript)

                        // Brain: log tool-triggered events for any dispatch that ran the tool
                        dispatchResult.let { r ->
                            val hints = when (r) {
                                is ToolDispatcher.DispatchResult.Done        -> r.hints
                                is ToolDispatcher.DispatchResult.LlmFollowUp -> r.hints
                                is ToolDispatcher.DispatchResult.AugmentedLlm -> r.hints
                                else                                          -> null
                            }
                            hints?.let { h ->
                                when (h.toolName) {
                                    "media_control" -> {
                                        val action = h.input.param("action")
                                        if (action == "play" || action == "shuffle" || action == "resume")
                                            brainEngine.collector.onMediaPlay()
                                        else if (action == "pause" || action == "stop")
                                            brainEngine.collector.onMediaStop()
                                    }
                                    "open_app" -> brainEngine.collector.onAppOpen(
                                        h.input.param("packageName").ifBlank { h.input.transcript }
                                    )
                                    "alarm" -> brainEngine.collector.onAlarmSet()
                                    "timer" -> brainEngine.collector.onTimerSet()
                                }
                            }
                        }

                        when (val r = dispatchResult) {
                            is ToolDispatcher.DispatchResult.Denied -> {
                                speakAndRecord(r.message)
                                machine.transition(JarvisState.Listening)
                                syncState(JarvisState.Listening)
                                continue
                            }
                            is ToolDispatcher.DispatchResult.SilentExit -> {
                                closeSessionAsync()
                                releaseResources()
                                backToWakeWord()
                                return@launch
                            }
                            is ToolDispatcher.DispatchResult.Done -> {
                                if (r.spokenFeedback.isNotBlank()) speakAndRecord(r.spokenFeedback)
                                machine.transition(JarvisState.Listening)
                                syncState(JarvisState.Listening)
                                continue
                            }
                            is ToolDispatcher.DispatchResult.Failed -> {
                                speakAndRecord(r.message)
                                machine.transition(JarvisState.Listening)
                                syncState(JarvisState.Listening)
                                continue
                            }
                            is ToolDispatcher.DispatchResult.AugmentedLlm -> {
                                val response = callLlm(r.augmentedTranscript, isOnline)
                                speakAndRecord(response)
                                machine.transition(JarvisState.Listening)
                                syncState(JarvisState.Listening)
                                continue
                            }
                            is ToolDispatcher.DispatchResult.LlmFollowUp -> {
                                if (r.spokenFeedback.isNotBlank()) speakAndRecord(r.spokenFeedback)
                                // fall through to LLM for follow-up
                            }
                        }
                    }

                    // ── OpenClaw remote routing (before local LLM fallback) ────────
                    if (openClawRouter.shouldRoute()) {
                        val route = openClawRouter.classify(transcript)

                        // REMOTE_LONG: speak acknowledgement first so there's no silence
                        if (route == com.jarvis.assistant.remote.openclaw.RouteType.REMOTE_LONG) {
                            ttsEngine.speak("Looking into that.")
                        }

                        val clawResult = openClawRouter.execute(
                            transcript,
                            sessionId,
                            llmRouter.conversationStore.getRecentMessages(6)
                        )
                        LatencyTracker.mark("OPENCLAW_COMPLETE")

                        when (clawResult) {
                            is OpenClawExecutionResult.Success -> {
                                speakAndRecord(clawResult.spokenSummary)
                                machine.transition(JarvisState.Listening)
                                syncState(JarvisState.Listening)
                                continue
                            }
                            is OpenClawExecutionResult.Failure -> {
                                Log.w(TAG, "OpenClaw failed: ${clawResult.error.spokenMessage}")
                                // Fall through to local LLM — do not speak the error unless
                                // it was an explicit configuration problem
                                if (clawResult.error is com.jarvis.assistant.remote.openclaw.OpenClawError.AuthFailed ||
                                    clawResult.error is com.jarvis.assistant.remote.openclaw.OpenClawError.NotConfigured) {
                                    speakAndRecord(clawResult.error.spokenMessage)
                                    machine.transition(JarvisState.Listening)
                                    syncState(JarvisState.Listening)
                                    continue
                                }
                                // Transient failures (timeout, unreachable, dropped): fall through silently
                            }
                            OpenClawExecutionResult.Bypassed -> { /* LOCAL_FAST or disabled — fall through */ }
                        }
                    }

                    // ── LLM inference via PromptAssembler + streaming ─────────────
                    LatencyTracker.mark("LLM_REQUEST_START")
                    streamAndSpeak(transcript, isOnline)

                    // If an implicit memory was stored from this turn, give a brief
                    // verbal cue so the user knows something was retained.
                    if (implicitMemoryStored && settings.voiceResponse) {
                        ttsEngine.speak("Noted.")
                    }

                    // Schedule a follow-up if this was a personal update with a
                    // future event or stress signal worth checking in on later.
                    if (convIntent == ConversationIntent.PERSONAL_UPDATE) {
                        val followUp = FollowUpExtractor.extract(transcript)
                        if (followUp != null) {
                            scope.launch(Dispatchers.IO) { followUpRepo.schedule(followUp) }
                        }
                    }

                    machine.transition(JarvisState.Listening)
                    syncState(JarvisState.Listening)
                }

            } catch (e: CancellationException) {
                throw e   // service stopped or silence() called — do not restart
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline error: ${e.message}", e)
                closeSessionAsync()
                releaseResources()
                backToWakeWord()
            }
        }
    }

    // ── Conversational check-in dispatch ─────────────────────────────────────

    /**
     * Called by [ConversationalProactiveEngine] on [Dispatchers.Main] when a
     * follow-up or gap check-in is due.
     *
     * Only fires when Jarvis is idle. Uses the same suppress/restore wake pattern
     * as reminder delivery so the TTS doesn't re-trigger the pipeline.
     */
    private suspend fun dispatchConversationalCheckIn(message: String) {
        if (!machine.isIn<JarvisState.IdleWake>()) return

        if (!settings.voiceResponse) {
            JarvisNotificationHelper.postReminder(context, "Jarvis", message)
            return
        }

        wakeDetector?.stop()
        wakeDetector = null
        audioFocus.requestFocus()
        machine.forceTransition(JarvisState.Speaking)
        syncState(JarvisState.Speaking)
        ttsEngine.speak(message)
        audioFocus.abandonFocus()
        backToWakeWord()
    }

    // ── Barge-in handling ─────────────────────────────────────────────────────

    /**
     * Called (on Main) the moment [BargeInDetector] detects sustained speech
     * while Jarvis is talking.  Captures a snapshot of how much was spoken and
     * how much remained, so the next user turn can be classified against it
     * (CONTINUE / CLARIFICATION / CORRECTION / REPLACEMENT / URGENT / UNRELATED).
     */
    private fun handleBargeIn() {
        if (!machine.isIn<JarvisState.Speaking>()) return
        Log.d(TAG, "Barge-in — cancelling TTS")
        ttsEngine.stopSpeaking()
        bargeIn.stop()

        // Snapshot unfinished reply so the next turn can decide whether to resume
        val spoken  = currentSpokenSoFar.trim()
        val pending = currentPendingTail.trim()
        if (spoken.isNotBlank() || pending.isNotBlank()) {
            lastInterrupted = ResumableResponse(
                userTranscript = currentTurnTranscript,
                spokenSoFar    = spoken,
                pendingTail    = pending,
                topic          = spoken.take(60).ifBlank { pending.take(60) }
            )
            Log.d(TAG, "Stored resumable: spoken='${spoken.take(40)}' pending='${pending.take(40)}'")
        }

        machine.transition(JarvisState.Interrupted)
        syncState(JarvisState.Interrupted)
        machine.transition(JarvisState.Listening)
        syncState(JarvisState.Listening)
    }

    // ── LLM call ─────────────────────────────────────────────────────────────

    /**
     * Build a fully-assembled message list (system prompt + context + memories
     * + history) via [PromptAssembler], then call [LlmRouter.completeWithMessages].
     */
    private suspend fun callLlm(transcript: String, isOnline: Boolean): String {
        if (!isOnline) {
            machine.transition(JarvisState.OfflineFallback)
            syncState(JarvisState.OfflineFallback)
            val fallback = OfflineManager.offlineLlmFallback(transcript)
            machine.transition(JarvisState.Thinking)
            return fallback
        }

        return try {
            llmRouter.conversationStore.addMessage("user", transcript)
            val history = llmRouter.conversationStore.getContextMessages()
                .filter { it.role != "system" }
            val messages = promptAssembler.assemble(
                transcript, history, maxMemories = 2,
                speakerContext = sessionSpeaker,
                presence       = currentPresence()
            )
            llmRouter.completeWithMessages(messages)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "LLM error: ${e.message}", e)
            if (!contextEngine.isOnline()) {
                machine.transition(JarvisState.OfflineFallback)
                syncState(JarvisState.OfflineFallback)
                OfflineManager.offlineLlmFallback(transcript)
            } else {
                "Hmm, that didn't go through. Try me again?"
            }
        }
    }

    // ── Streaming LLM + TTS ───────────────────────────────────────────────────

    /**
     * Stream the LLM response sentence by sentence and speak each sentence as
     * soon as it arrives, so the first word of audio plays ~1 s after STT ends
     * instead of waiting 3–5 s for the full response.
     *
     * Compared with [callLlm] + [speakAndRecord]:
     *   • Transitions to [JarvisState.Speaking] on the first sentence (not after
     *     the entire response is received).
     *   • Barge-in is honoured mid-stream: if [handleBargeIn] fires, TTS stops
     *     and subsequent sentences are collected but not spoken.
     *   • The full response is saved to [memoryWriter] and [DeviceStateStore]
     *     after all tokens have been received.
     */
    private suspend fun streamAndSpeak(transcript: String, isOnline: Boolean) {
        if (!isOnline) {
            machine.transition(JarvisState.OfflineFallback)
            syncState(JarvisState.OfflineFallback)
            val fallback = OfflineManager.offlineLlmFallback(transcript)
            machine.transition(JarvisState.Thinking)
            speakAndRecord(fallback)
            return
        }

        // Shared buffers for barge-in snapshot — populated as tokens arrive.
        currentSpokenSoFar = ""
        currentPendingTail = ""
        currentTurnTranscript = transcript

        try {
            llmRouter.conversationStore.addMessage("user", transcript)
            val history  = llmRouter.conversationStore.getContextMessages()
                .filter { it.role != "system" }
            val messages = promptAssembler.assemble(
                transcript, history, maxMemories = 4,
                speakerContext = sessionSpeaker,
                presence       = currentPresence()
            )

            // ── Agentic tool chaining (up to MAX_TOOL_HOPS per turn) ──────────────
            // FC-capable providers return LlmResult; non-FC providers return null
            // and fall through to the streaming path below unchanged.
            val schemas = toolRegistry.availableSchemas(isOnline)
            if (schemas.isNotEmpty()) {
                val chainMessages = messages.toMutableList()
                var hopsUsed = 0
                var fcHandled = false

                while (hopsUsed < MAX_TOOL_HOPS) {
                    val fcResult = llmRouter.completeWithFunctionCalling(chainMessages, schemas)
                    when {
                        fcResult == null -> break   // provider has no FC — fall through to streaming

                        fcResult is LlmResult.Text -> {
                            if (fcResult.content.isNotBlank()) {
                                llmRouter.conversationStore.addMessage("assistant", fcResult.content)
                                speakAndRecord(fcResult.content)
                                fcHandled = true
                            }
                            break
                        }

                        fcResult is LlmResult.MultiToolCall -> {
                            // ── Plan path ─────────────────────────────────────────
                            // Route multi-step calls through PlanRunner so the user
                            // confirms once, every step is journalled, and undo
                            // works in reverse on the next turn.  Single-call
                            // MultiToolCalls fall through to the parallel execution
                            // below (Proposal.SingleStep).
                            hopsUsed++
                            val proposal = planRunner.proposeFromMultiCall(fcResult, transcript)
                            when (proposal) {
                                is com.jarvis.assistant.runtime.plan.PlanRunner.Proposal.Pending -> {
                                    pendingPlan = proposal.plan
                                    llmRouter.conversationStore.addMessage("assistant", proposal.confirmation)
                                    speakAndRecord(proposal.confirmation)
                                    fcHandled = true
                                    break
                                }
                                is com.jarvis.assistant.runtime.plan.PlanRunner.Proposal.Empty -> {
                                    Log.d(TAG, "MultiToolCall didn't resolve to any registered tool")
                                    break
                                }
                                is com.jarvis.assistant.runtime.plan.PlanRunner.Proposal.SingleStep -> {
                                    // Fall through to the existing parallel path
                                    // for the genuinely single-call case.
                                }
                            }

                            val callList = fcResult.calls
                            val feedbacks = callList.map { tc ->
                                async(Dispatchers.IO) {
                                    val tool = toolRegistry.findByName(tc.toolName) ?: return@async null
                                    @Suppress("UNCHECKED_CAST")
                                    val argsMap = try {
                                        (NetworkClient.gson.fromJson(tc.argsJson, Map::class.java) as Map<*, *>)
                                            .entries.associate { (k, v) -> k.toString() to v.toString() }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Malformed tool args for ${tc.toolName}: ${e.message}")
                                        emptyMap()
                                    }
                                    val result = toolRegistry.execute(context, tool, ToolInput(transcript, argsMap))
                                    val fb = when (result) {
                                        is ToolResult.Success -> result.spokenFeedback
                                        is ToolResult.Failure -> result.spokenFeedback
                                        else -> null
                                    }
                                    fb?.let { "[${tool.name}]: $it" }
                                }
                            }.awaitAll().filterNotNull()

                            if (feedbacks.isEmpty() || hopsUsed >= MAX_TOOL_HOPS) {
                                val combined = feedbacks.joinToString(". ")
                                if (combined.isNotBlank()) {
                                    llmRouter.conversationStore.addMessage("assistant", combined)
                                    speakAndRecord(combined)
                                    fcHandled = true
                                }
                                break
                            }
                            feedbacks.forEach { chainMessages.add(Message("assistant", it)) }
                        }

                        fcResult is LlmResult.ToolCall -> {
                            hopsUsed++
                            val tool = toolRegistry.findByName(fcResult.toolName)
                            if (tool == null) break   // unknown tool name — fall through

                            @Suppress("UNCHECKED_CAST")
                            val argsMap = try {
                                (NetworkClient.gson.fromJson(fcResult.argsJson, Map::class.java) as Map<*, *>)
                                    .entries.associate { (k, v) -> k.toString() to v.toString() }
                            } catch (e: Exception) {
                                Log.w(TAG, "Malformed tool args for ${fcResult.toolName}: ${e.message}")
                                emptyMap()
                            }

                            val result = toolRegistry.execute(context, tool, ToolInput(transcript, argsMap))
                            val feedback = when (result) {
                                is ToolResult.Success -> result.spokenFeedback
                                is ToolResult.Failure -> result.spokenFeedback
                                else -> null
                            }

                            if (feedback == null || result is ToolResult.Failure || hopsUsed >= MAX_TOOL_HOPS) {
                                // Failure or chain cap — deliver raw feedback and stop
                                if (feedback != null) {
                                    llmRouter.conversationStore.addMessage("assistant", feedback)
                                    speakAndRecord(feedback)
                                    fcHandled = true
                                }
                                break
                            }

                            // Inject tool result so the LLM can decide to chain or wrap up
                            chainMessages.add(Message("assistant", "[${tool.name}]: $feedback"))
                        }

                        else -> break
                    }
                }
                if (fcHandled) return   // agentic chain complete — skip streaming path
            }

            val fullResponse    = StringBuilder()
            var speakingStarted = false

            llmRouter.streamWithMessages(messages).collect { sentence ->
                fullResponse.append(sentence).append(" ")

                // Transition to Speaking + start barge-in detector on first sentence
                if (!speakingStarted) {
                    speakingStarted = true
                    machine.transition(JarvisState.Speaking)
                    DeviceStateStore.update { copy(ttsPlaying = true) }
                    syncState(JarvisState.Speaking)
                    if (settings.voiceResponse) {
                        LatencyTracker.mark("TTS_START")
                        bargeIn.start()
                    }
                }

                // Snapshot state once — handleBargeIn() on Main can transition it
                // between two separate isIn() calls, which would bucket a sentence
                // into spokenSoFar but never actually speak it, corrupting the resume snapshot.
                val stillSpeaking = machine.isIn<JarvisState.Speaking>()
                if (stillSpeaking) {
                    currentSpokenSoFar += "$sentence "
                } else {
                    // Already interrupted — remaining sentences are unspoken
                    currentPendingTail += "$sentence "
                }

                if (settings.voiceResponse && stillSpeaking) {
                    ttsEngine.speak(sentence)
                }
            }

            if (settings.voiceResponse && speakingStarted) bargeIn.stop()
            DeviceStateStore.update { copy(ttsPlaying = false) }
            LatencyTracker.mark("PIPELINE_DONE")

            // Persist the complete response after all tokens have arrived
            val responseText = fullResponse.toString().trim()
            if (responseText.isNotBlank()) {
                val base      = ResponseFormatter.format(responseText)
                val formatted = if (drivingModeManager.isDriving)
                    base.split(Regex("(?<=[.!?])\\s+")).take(2).joinToString(" ")
                else base
                memoryWriter.writeTurn(sessionId, "assistant", formatted)
                DeviceStateStore.update { copy(lastAssistantResponse = formatted) }
            }

            // Compress oldest turn-pairs if history is near the context window ceiling
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                conversationCompressor.maybeCompress()
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "LLM streaming error: ${e.message}", e)
            bargeIn.stop()
            DeviceStateStore.update { copy(ttsPlaying = false) }
            if (!contextEngine.isOnline()) {
                machine.transition(JarvisState.OfflineFallback)
                syncState(JarvisState.OfflineFallback)
                speakAndRecord(OfflineManager.offlineLlmFallback(transcript))
            } else {
                speakAndRecord("Hmm, that didn't go through. Try me again?")
            }
        } finally {
            // Clear shared references — next turn starts with fresh buffers
            currentSpokenSoFar = ""
            currentPendingTail = ""
            currentTurnTranscript = ""
        }
    }

    /**
     * Resume an interrupted reply via a fresh LLM call instead of replaying the
     * original tokens.  The model is shown what was actually spoken before the
     * interruption and nudged to pick up with a short natural connector
     * ("Right, so…", "Yeah,") without repeating any of those words.
     *
     * The previous assistant message in [ConversationStore] contains the full
     * pre-interrupt response (spoken + unspoken tail); it's stripped and
     * replaced with `spokenSoFar` so the LLM's view of history matches what
     * the user actually heard.
     */
    private suspend fun resumeContinuation(
        resumable: ResumableResponse,
        isOnline: Boolean
    ) {
        if (!isOnline) {
            speakAndRecord(OfflineManager.offlineLlmFallback(resumable.userTranscript))
            return
        }

        // Drop the stale full-response assistant turn from history so the
        // next stream's auto-persist doesn't leave two assistant entries
        // back-to-back; we'll put back a merged entry once the resume
        // stream completes.
        val spoken = resumable.spokenSoFar.trim()
        llmRouter.conversationStore.dropLastAssistant()

        val history = llmRouter.conversationStore.getContextMessages()
            .filter { it.role != "system" }
            .toMutableList()
        // Inject what the user actually heard as an assistant turn in the
        // LLM's view of history, then a brief synthetic user cue.  Only the
        // LLM sees these — they do not touch ConversationStore.
        history.add(Message("assistant", spoken))
        history.add(
            Message(
                "user",
                "[You were cut off. Pick up naturally from where you stopped. " +
                "Open with a short connector like \"Right, so…\" or \"Yeah,\" " +
                "and do not repeat anything you already said. One or two sentences.]"
            )
        )

        val messages = promptAssembler.assemble(
            userQuery           = resumable.userTranscript,
            conversationHistory = history,
            maxMemories         = 2,
            speakerContext      = sessionSpeaker,
            presence            = currentPresence()
        )

        currentSpokenSoFar    = ""
        currentPendingTail    = ""
        currentTurnTranscript = resumable.userTranscript

        val fullResponse    = StringBuilder()
        var speakingStarted = false

        try {
            llmRouter.streamWithMessages(messages).collect { sentence ->
                fullResponse.append(sentence).append(" ")
                if (!speakingStarted) {
                    speakingStarted = true
                    machine.transition(JarvisState.Speaking)
                    DeviceStateStore.update { copy(ttsPlaying = true) }
                    syncState(JarvisState.Speaking)
                    if (settings.voiceResponse) bargeIn.start()
                }
                val stillSpeaking = machine.isIn<JarvisState.Speaking>()
                if (stillSpeaking) currentSpokenSoFar += "$sentence "
                else currentPendingTail += "$sentence "
                if (settings.voiceResponse && stillSpeaking) ttsEngine.speak(sentence)
            }
            if (settings.voiceResponse && speakingStarted) bargeIn.stop()
            DeviceStateStore.update { copy(ttsPlaying = false) }

            val responseText = fullResponse.toString().trim()
            if (responseText.isNotBlank()) {
                val formatted = ResponseFormatter.format(responseText)
                memoryWriter.writeTurn(sessionId, "assistant", formatted)
                DeviceStateStore.update { copy(lastAssistantResponse = formatted) }
                // Merge the just-streamed resume with the spoken prefix so
                // conversation history has one assistant turn, not two.
                val merged = if (spoken.isBlank()) formatted else "$spoken $formatted"
                llmRouter.conversationStore.replaceLastAssistant(merged)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Resume error: ${e.message}", e)
            bargeIn.stop()
            DeviceStateStore.update { copy(ttsPlaying = false) }
        } finally {
            currentSpokenSoFar    = ""
            currentPendingTail    = ""
            currentTurnTranscript = ""
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun speakAndRecord(text: String) {
        val base = ResponseFormatter.format(text)
        // In driving mode, truncate to first 2 sentences to keep responses brief
        val formatted = if (drivingModeManager.isDriving) {
            base.split(Regex("(?<=[.!?])\\s+")).take(2).joinToString(" ")
        } else base
        memoryWriter.writeTurn(sessionId, "assistant", formatted)
        brainEngine.collector.onJarvisResponse(formatted)
        DeviceStateStore.update { copy(lastAssistantResponse = formatted) }

        machine.transition(JarvisState.Speaking)
        DeviceStateStore.update { copy(ttsPlaying = true) }
        syncState(JarvisState.Speaking)

        if (settings.voiceResponse) {
            LatencyTracker.mark("TTS_START")
            bargeIn.start()
            ttsEngine.speak(formatted)
            bargeIn.stop()
        }

        DeviceStateStore.update { copy(ttsPlaying = false) }
        LatencyTracker.mark("PIPELINE_DONE")
    }

    /**
     * Compute a fresh [Presence] from the same signals the proactive engine
     * already observes.  Called before each LLM assembly so the system prompt
     * carries a current-moment line ("evening, user winding down" etc.) —
     * this is how continuity across turns is expressed to the model.
     */
    private fun currentPresence(): Presence {
        val speechState = speechStateSource.getSpeechState()
        val lastUser   = lastSeenTracker.lastUserTurnMs.takeIf { it > 0L }
        return Presence.compute(
            nowMs             = System.currentTimeMillis(),
            lastInteractionMs = lastUser,
            isJarvisSpeaking  = speechState.isSpeaking,
            isJarvisListening = speechState.isListening,
            isDriving         = drivingModeManager.isDriving
        )
    }

    private fun backToWakeWord() {
        // CallRecovery → IdleWake is in the validated transition graph.
        // All other call states use forceTransition: an exception can abort
        // the call coordinator at any intermediate state (IncomingCallAlert,
        // WaitingCallCommand, ExecutingCallAction), none of which have IdleWake
        // as a valid transition.  forceTransition matches the existing pattern
        // used for ServiceStopped (another external-interrupt path).
        if (machine.current.isCallState && !machine.isIn<JarvisState.CallRecovery>()) {
            machine.forceTransition(JarvisState.IdleWake)
        } else {
            machine.transition(JarvisState.IdleWake)
        }
        syncState(JarvisState.IdleWake)
        startWakeWordDetection()
    }

    /** Release audio resources without stopping the service. */
    private fun releaseResources() {
        activeCapture?.release()
        activeCapture = null
        bluetoothSco.disconnect()  // Phase 4
        audioFocus.abandonFocus()  // Phase 5
        DeviceStateStore.update { copy(headsetConnected = bluetoothSco.isHeadsetConnected) }
    }

    /**
     * Synchronously flush the current session's raw text to the DB before the
     * coroutine scope is cancelled.  Called only from [stop] where we know the
     * scope is about to be torn down.  LLM work (summarisation, compilation) is
     * intentionally skipped here — [start] already drains uncompiled sources via
     * [KnowledgeCompiler.compilePending] on every startup.
     */
    private fun flushSessionToDb() {
        if (!sessionOpen) return
        sessionOpen = false
        val id = sessionId
        runBlocking(Dispatchers.IO) {
            try {
                // Build a heuristic summary from in-memory user turns so this
                // session produces a retrievable MemoryEntry even without LLM access.
                // closeSession() only writes a SUMMARY MemoryEntry when summary != null,
                // so passing null here means the session is forever invisible to retrieval.
                val userTurns = llmRouter.conversationStore.getContextMessages()
                    .filter { it.role == "user" }
                val heuristicSummary = if (userTurns.isNotEmpty()) {
                    userTurns.joinToString(". ") { it.content.take(120) }.take(400)
                } else null
                memoryWriter.closeSession(id, summary = heuristicSummary)
            } catch (e: Exception) {
                Log.w(TAG, "flushSession: closeSession failed: ${e.message}")
            }
            try {
                val turns = llmRouter.conversationStore.getContextMessages()
                val sessionText = turns.joinToString("\n") { "${it.role}: ${it.content}" }
                if (sessionText.isNotBlank()) {
                    knowledgeCompiler.ingest(sessionText, KnowledgeSource.VOICE_TRANSCRIPT)
                    // compilePending() involves LLM calls — deferred to next startup
                }
            } catch (e: Exception) {
                Log.w(TAG, "flushSession: ingest failed: ${e.message}")
            }
        }
    }

    private fun closeSessionAsync() {
        if (!sessionOpen) return
        sessionOpen = false
        val id = sessionId
        scope.launch(Dispatchers.IO) {
            summarizer.summarizeAndClose(id)
        }
        scope.launch(Dispatchers.IO) {
            try {
                val turns = llmRouter.conversationStore.getContextMessages()
                val sessionText = turns.joinToString("\n") { "${it.role}: ${it.content}" }
                if (sessionText.isNotBlank()) {
                    knowledgeCompiler.ingest(sessionText, KnowledgeSource.VOICE_TRANSCRIPT)
                    knowledgeCompiler.compilePending(batchSize = 3)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Knowledge ingest failed: ${e.message}")
            }
        }
        val now = System.currentTimeMillis()
        if (now - lastCompactionMs > 24L * 3_600_000) {
            lastCompactionMs = now
            scope.launch(Dispatchers.IO) {
                try {
                    retentionPolicy.compact()
                    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        .format(java.util.Date(now))
                    knowledgeCompiler.compileDailySummary(today)
                } catch (e: Exception) {
                    Log.w(TAG, "Knowledge compaction failed: ${e.message}")
                }
            }
        }
    }

    private fun syncState(state: JarvisState) {
        // When any call becomes active, the telephony system takes audio focus.
        // Abandon Jarvis's claim so there is no conflict.
        if (state is JarvisState.CallActive) {
            audioFocus.abandonFocus()
            val current = DeviceStateStore.current
            DeviceStateStore.update { copy(activeCallInfo = current.incomingCallInfo) }
        }
        if (state is JarvisState.OutgoingCallActive) {
            // Audio focus was already abandoned in onOutgoingCallStarted() before
            // this call, but call it again defensively in case syncState is used
            // via a different code path in the future.
            audioFocus.abandonFocus()
        }
        DeviceStateStore.update {
            copy(runtimeState = state, lastStateChangeTime = System.currentTimeMillis())
        }
        onStateChange(state)
    }

    private fun isStopCommand(t: String): Boolean {
        val s = t.lowercase().trim()
        return s == "stop"       || s == "bye"           || s == "goodbye"   ||
               s == "sleep"      || s == "go to sleep"   || s == "good night" ||
               s == "that's all" || s == "that's enough" || s == "cancel"    ||
               s.startsWith("stop listening") || s.startsWith("goodbye jarvis")
    }
}
