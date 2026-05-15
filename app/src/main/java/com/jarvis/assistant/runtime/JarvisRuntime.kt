package com.jarvis.assistant.runtime

import android.content.Context
import android.os.Build
import android.util.Log
import com.jarvis.assistant.audio.AudioFocusManager
import com.jarvis.assistant.audio.BargeInDetector
import com.jarvis.assistant.audio.BluetoothScoManager
import com.jarvis.assistant.audio.GoogleWakeWordDetector
import com.jarvis.assistant.audio.SpeechCapture
import com.jarvis.assistant.audio.TFLiteWakeWordDetector
import com.jarvis.assistant.JarvisApp
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
import com.jarvis.assistant.reporting.github.autoReporting
import com.jarvis.assistant.core.events.EventAdapters
import com.jarvis.assistant.core.events.RecentEventBuffer
import com.jarvis.assistant.core.events.adapters.BatteryEventAdapter
import com.jarvis.assistant.core.events.adapters.DrivingModeEventAdapter
import com.jarvis.assistant.core.events.adapters.ForegroundAppEventAdapter
import com.jarvis.assistant.core.events.adapters.HomeAssistantEventAdapter
import com.jarvis.assistant.core.events.adapters.NetworkEventAdapter
import com.jarvis.assistant.core.events.adapters.ProximityEventAdapter
import com.jarvis.assistant.core.events.adapters.TelephonyEventAdapter
import com.jarvis.assistant.core.safety.ConfirmationGate
import com.jarvis.assistant.core.safety.Sanitizer
import com.jarvis.assistant.core.telemetry.DecisionTraceStore
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
import com.jarvis.assistant.audio.stt.TranscriptCorrector
import com.jarvis.assistant.audio.stt.VocabularyBiaser
import com.jarvis.assistant.tools.device.AppAliasStore
import com.jarvis.assistant.tools.device.AppResolver
import com.jarvis.assistant.modes.JarvisMode
import com.jarvis.assistant.voice.attention.AttentionDecision
import com.jarvis.assistant.voice.attention.AttentionGate
import com.jarvis.assistant.voice.attention.AttentionSignals
import com.jarvis.assistant.core.events.input.HomeAssistantNotificationClassifier
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    private lateinit var memoryPolicy      : com.jarvis.assistant.core.decisions.MemoryPolicy
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
    // Shared contact-aware STT post-processor.  Wired into [speechCapture]'s
    // N-best selector hook during start() so every captured utterance flows
    // through vocabulary biasing + phonetic correction + contact repair
    // before any intent parsing sees it.
    // Tier A2 — ONE shared ContactLookup for the whole runtime.  Passed to
    // ToolRegistry.buildDefault, FollowUpCoordinator, and TranscriptCorrector
    // so contact resolution is consistent and the Jaro-Winkler full-scan
    // cache is computed once per session.
    private val sharedContactLookup: ContactLookup = ContactLookup(context)
        .also {
            Log.d(TAG, "[CONTACT_LOOKUP_SHARED_INSTANCE_CREATED] " +
                "owner=JarvisRuntime consumers=stt,followup,tools")
        }
    // Kept as a separate field name for the TranscriptCorrector consumer to
    // remain greppable, but it is the same object as [sharedContactLookup].
    private val sttContactLookup: ContactLookup get() = sharedContactLookup
    private val aliasLearningStore =
        com.jarvis.assistant.voice.learning.AliasLearningStore(context)
    private val transcriptCorrector =
        TranscriptCorrector(sharedContactLookup, aliasLearningStore)
    private val ttsEngine      = TtsEngine(context)
    // AttentionGate decides whether each captured transcript was meant for
    // Jarvis or was overheard human-to-human speech.  Gated by
    // VoiceFeatureFlags.ATTENTION_GATE_ENABLED (default ON).
    private val attentionGate  = AttentionGate()
    // ── Pending messaging follow-up ─────────────────────────────────────
    // When a messaging command arrives with missing slots ("send a WhatsApp"
    // with no recipient/body), we park a PendingMessageIntent and intercept
    // the NEXT user utterance before AttentionGate filters it.  Cleared on
    // execute, decline, or 20s expiry.
    @Volatile private var pendingMessageIntent:
        com.jarvis.assistant.tools.device.messaging.PendingMessageIntent? = null

    /**
     * Same idea as [pendingMessageIntent] but for Todoist reminder/task
     * flows.  When the parser produces a usable match that's missing a
     * time (or other follow-up slot), the runtime parks one of these,
     * speaks a short prompt, and intercepts the NEXT user turn before
     * any other routing.  Cleared on execute / expiry.
     */
    @Volatile private var pendingTodoistTask:
        com.jarvis.assistant.todoist.PendingTodoistTask? = null

    /**
     * Todoist orchestrator.  Pure of UI / TTS — returns
     * [com.jarvis.assistant.todoist.TodoistReminderRouter.RouterAction]
     * the runtime translates into [speakAndRecord] + state changes.
     */
    /** UI-facing drain of the Todoist offline queue. */
    suspend fun todoistDrainOfflineQueue(): Int =
        todoistReminderRouter.drainOfflineQueue()

    private val todoistReminderRouter by lazy {
        val store = settings
        com.jarvis.assistant.todoist.TodoistReminderRouter(
            client = {
                com.jarvis.assistant.todoist.TodoistClient(
                    tokenProvider = { store.todoistApiToken }
                )
            },
            settingsProvider = {
                com.jarvis.assistant.todoist.TodoistSettingsRepository(store).snapshot()
            },
            offlineQueue = {
                com.jarvis.assistant.todoist.TodoistOfflineQueue(context)
            },
        )
    }
    // Tier-B mode controller — owns the current JarvisMode and consumes
    // ambient context snapshots to auto-switch (NORMAL ↔ DRIVING ↔ NIGHT).
    // Gated by VoiceFeatureFlags.JARVIS_MODES_ENABLED (default OFF).
    private val modeController = com.jarvis.assistant.modes.ModeController()
    // Tier-C executive controller — task / goal / attention tracker.  Gated
    // by VoiceFeatureFlags.EXECUTIVE_CONTROLLER_ENABLED (default OFF); when
    // off, decide() returns SILENT_NOTIFY → dispatcher downgrades to
    // notification (existing behaviour preserved).
    private val executiveController = com.jarvis.assistant.executive.ExecutiveController()
    // Tier-B ambient aggregator — single StateFlow producer of
    // AmbientContextSnapshot.  Gated by AMBIENT_CONTEXT_ENABLED (default
    // OFF).  Refreshed every 5 s by its own coroutine and pushed into
    // [modeController] each tick so modes follow context automatically.
    private val ambientContext = com.jarvis.assistant.context.AmbientContextAggregator(
        refresh = { snapshotAmbientContext() }
    )
    // Set true by handleBargeIn() so any active stream-collection block can
    // disambiguate "the user interrupted me" from "the first-token watchdog
    // fired" inside a generic catch (CancellationException).  Reset before
    // every new response stream.
    private val bargeInFired = java.util.concurrent.atomic.AtomicBoolean(false)
    // Instrumentation facade over ToolRegistry — emits the [ROUTE_*] logs
    // the spec asks for at every routing inflection without changing the
    // existing local-vs-OpenClaw branching.
    private val localFirstRouter by lazy {
        com.jarvis.assistant.voice.routing.LocalFirstRouter(toolRegistry)
    }
    /**
     * InstantCommandRouter — the first gate after STT.  Local / device
     * commands (time, battery, call, WhatsApp, alarm, smart-home, …)
     * short-circuit here and NEVER reach OpenClaw / memory retrieval /
     * the LLM.  Returns NoMatch for anything that isn't on the allowlist;
     * the caller falls through to the existing LocalFirstRouter +
     * OpenClaw + LLM cascade.
     */
    private val instantCommandRouter by lazy {
        com.jarvis.assistant.voice.routing.InstantCommandRouter(toolRegistry)
    }

    /**
     * RecentActionContextStore — populated after every successful
     * local-tool dispatch.  Drives the contextual follow-up resolver:
     *   - "turn off" after "turn the flashlight on"
     *   - "show me the selfie" after a camera capture
     *   - "do that again" after any action
     */
    private val recentActionContext = com.jarvis.assistant.runtime.context
        .RecentActionContextStore()
    // Stash the most recent TranscriptCorrector score so the gate can read it.
    @Volatile private var lastCorrectorScore: Int = 0
    private val bargeIn        = BargeInDetector(onBargeIn = ::handleBargeIn)
    private var wakeDetector: WakeWordDetector? = null

    // Proactive awareness engine
    private val callSource        = AppCallContextSource()
    private val speechStateSource = AppSpeechStateSource(machine)
    private lateinit var proactiveEngine   : ProactiveEngine
    private lateinit var ambientEmitter    : com.jarvis.assistant.ambient.AmbientProactiveEventEmitter
    private lateinit var preferenceEngine  : com.jarvis.assistant.preferences.ResponsePreferenceEngine
    /**
     * Scheduled-reminder engine — fires the 30m + 10m pre-warnings for
     * Calendar / Todoist / local reminders.  Hands events to
     * [scheduledReminderBridge] which dispatches through the shared
     * [com.jarvis.assistant.proactive.settings.ProactivityGate] so all
     * user policy (master / quiet / mode / cooldown) is honoured.
     */
    private var scheduledReminderEngine: com.jarvis.assistant.proactive.scheduled.ScheduledReminderEngine? = null
    private var scheduledReminderBridge: com.jarvis.assistant.proactive.scheduled.ScheduledReminderDispatchBridge? = null

    /**
     * User-visible Proactivity settings repository.  Exposed so the
     * Settings UI can collect updates against its StateFlow without
     * needing a fresh repo instance.
     */
    lateinit var proactivityRepository
        : com.jarvis.assistant.proactive.settings.ProactivitySettingsRepository
        private set

    /**
     * Trace-log accessor for the Proactivity diagnostics screen.  Lazy
     * because [proactiveEngine] is the writer and is constructed during
     * intensive init.
     */
    val proactiveEventsLogQuery
        : com.jarvis.assistant.proactive.settings.ProactiveEventsLogQuery by lazy {
        com.jarvis.assistant.proactive.settings.ProactiveEventsLogQuery(
            com.jarvis.assistant.core.telemetry.DecisionTraceStore(
                com.jarvis.assistant.memory.db.JarvisDatabase
                    .getInstance(context).decisionTraceDao()
            )
        )
    }

    // Conversational follow-up + last-seen tracking
    private lateinit var lastSeenTracker   : LastSeenTracker
    private lateinit var followUpRepo      : FollowUpRepository
    private lateinit var convProactiveEngine: ConversationalProactiveEngine

    // OpenClaw — remote routing for complex queries (outbound) + node client (inbound)
    private val openClawRepo   = com.jarvis.assistant.remote.openclaw.OpenClawSettingsRepository(settings)
    private val openClawRouter = OpenClawRouter(openClawRepo)
    private val openClawNode   = com.jarvis.assistant.remote.openclaw.OpenClawNodeClient(
        settingsRepo      = openClawRepo,
        availableCommands = { toolRegistry.registeredNames },
        onInvoke          = { command, args ->
            val tool  = toolRegistry.findByName(command)
                ?: return@OpenClawNodeClient "Unknown command: $command"
            val input = com.jarvis.assistant.tools.framework.ToolInput(
                transcript = "openclaw:$command",
                params     = args.mapValues { it.value.toString() }
            )
            when (val result = toolRegistry.execute(context, tool, input)) {
                is com.jarvis.assistant.tools.framework.ToolResult.Success -> result.spokenFeedback
                is com.jarvis.assistant.tools.framework.ToolResult.Failure -> result.spokenFeedback
                else -> "Done."
            }
        }
    )

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

    // Coroutine scope — SupervisorJob so a child failure does not cancel the
    // whole scope.  autoReporting funnels any uncaught coroutine exception
    // through IssueReporter.reportHigh — without it, supervisor children
    // log to System.err and never reach the JarvisUncaughtHandler.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + autoReporting("runtime")
    )

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

    // Core event bus adapters — publish sensed signals to EventBus.
    // Owned here so lifecycle matches the runtime; detached in shutdown().
    private val eventAdapters = EventAdapters()

    // Recent-event ring buffer so composite triggers can reason about
    // cross-stream history ("SSID changed 30s ago AND driving mode on").
    private val recentEventBuffer = RecentEventBuffer()

    // Durable known-SSID set for UnfamiliarSsidTrigger.
    private val knownSsidStore = com.jarvis.assistant.core.learning.KnownSsidStore(context)

    // Buffer of recent successful tool calls so SaveRoutineTool can persist
    // a sequence the user says "save that as a routine called X" on.
    private val recentToolCallBuffer = com.jarvis.assistant.core.routines.RecentToolCallBuffer()

    // Observes TOOL_EXECUTED events to auto-propose routines after a
    // sequence recurs a few times in the same hour-of-day.
    private val routineSynthesizer = com.jarvis.assistant.core.routines.RoutineSynthesizer(context)

    // Presence layer — rolling topics and short-term expectations the
    // system prompt and scoring can consult every turn.
    private val conversationThreads = com.jarvis.assistant.core.presence.ConversationThreads(context)

    /**
     * Short-lived carrier for the last fact-style reply (location, weather,
     * battery, time-from-tool …).  Consumed by PromptAssembler on the next
     * LLM turn so follow-ups like "what number?" / "and the postcode?"
     * resolve against the previous answer instead of being misrouted to
     * an unrelated tool (e.g. clock).
     */
    private val recentFactCarrier = com.jarvis.assistant.followup.RecentFactCarrier()

    private val expectationStore by lazy {
        com.jarvis.assistant.core.presence.ExpectationStore(
            com.jarvis.assistant.memory.db.JarvisDatabase.getInstance(context).expectationDao()
        )
    }
    // Optional cloud sync (Firebase). No-op unless the user has opted in
    // and entered Firebase credentials in Settings.
    private val cloudSyncService by lazy {
        com.jarvis.assistant.core.sync.CloudSyncService(
            context = context,
            settings = settings,
            memoryFactDao = com.jarvis.assistant.memory.db.JarvisDatabase.getInstance(context).memoryFactDao(),
            savedRoutineDao = com.jarvis.assistant.memory.db.JarvisDatabase.getInstance(context).savedRoutineDao(),
        )
    }

    // Continuous-tick provider; subscribes to EventBus on start.
    // Built lazily so it captures proactiveEngine after initialize().
    private val agentContextProvider by lazy {
        com.jarvis.assistant.core.context.AgentContextProvider(
            contextEngine = contextEngine,
            presenceProvider = { currentPresence() },
            snapshotProvider = { proactiveEngine.lastSnapshot },
        )
    }

    // Shared cooldown so the ledger can be constructed before ProactiveEngine
    // and handed to both the engine and the tool dispatcher.
    private val sharedCooldownStore = com.jarvis.assistant.proactive.CooldownStore(
        dao = com.jarvis.assistant.memory.db.JarvisDatabase.getInstance(context).proactiveCooldownDao()
    )
    private val sharedActionLedger = com.jarvis.assistant.core.decisions.ActionLedger(
        cooldownStore = sharedCooldownStore,
        prefs = com.jarvis.assistant.core.decisions.ActionLedger.prefsFor(context),
    )

    // Cross-path confirmation layer for destructive tools. LOW-risk tools
    // don't touch it; MEDIUM/HIGH trigger a "are you sure?" handshake.
    private val confirmationGate = ConfirmationGate()

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
        promptAssembler = PromptAssembler(
            contextEngine, memoryReader, profileMemory, knowledgeQuery,
            sanitizer = Sanitizer(),
            conversationThreads = conversationThreads,
            expectationStore = expectationStore,
            recentFactCarrier = recentFactCarrier,
        )

        // Phase 7 — Orchestration
        reminderRepo = ReminderRepository(db.scheduledItemDao(), ReminderScheduler(context))

        // Referential-action store — powers "undo that" / "do the same for X".
        // In-memory only; lifetime matches the runtime.
        lastActionStore = com.jarvis.assistant.runtime.reference.LastActionStore()

        // Routine repository — persistent saved sequences.
        val routineRepository = com.jarvis.assistant.core.routines.RoutineRepository(db.savedRoutineDao())

        // Response preference engine — constructed before ToolRegistry so tools
        // receive the engine on first instantiation.
        val prefRepo = com.jarvis.assistant.preferences.ResponsePreferenceRepository(
            db.responsePreferenceDao()
        )
        preferenceEngine = com.jarvis.assistant.preferences.ResponsePreferenceEngine(prefRepo)
        JarvisApp.preferenceEngine = preferenceEngine

        // Tool registry
        toolRegistry = ToolRegistry.buildDefault(
            context                = context,
            settings               = settings,
            memoryRetriever        = memoryReader,
            reminderRepository     = reminderRepo,
            outgoingCallController = outgoingCallController,
            locationProvider       = locationProvider,
            llmRouter              = llmRouter,
            lastActionStore        = lastActionStore,
            actionLedger           = sharedActionLedger,
            routineRepository      = routineRepository,
            recentToolCallBuffer   = recentToolCallBuffer,
            planRunnerProvider     = { if (::planRunner.isInitialized) planRunner else null },
            expectationStore       = expectationStore,
            sharedContacts         = sharedContactLookup, // A2: one ContactLookup for whole runtime
            openClawRepo           = openClawRepo,        // enables OpenClawStatusTool
            profileMemory          = profileMemory,       // enables PersonalFactTool
            preferenceEngine       = this.preferenceEngine,
        )
        toolDispatcher = ToolDispatcher(
            context,
            toolRegistry,
            machine,
            lastActionStore,
            killSwitchProvider = { settings.toolExecutionDisabled },
            // Voice strict mode default OFF — owner is assumed; voice
            // identity upgrades trust, never blocks LOW/MEDIUM risk.
            voiceStrictModeProvider = { settings.voiceStrictMode },
            // Resolves to the engine's ledger once ProactiveEngine is built
            // below. Until then the lambda returns null and the dispatcher
            // records nothing, which matches legacy behaviour.
            actionLedgerProvider = { sharedActionLedger },
            confirmationGate = confirmationGate,
            recentToolCallBuffer = recentToolCallBuffer
        )
        memoryPolicy = com.jarvis.assistant.core.decisions.MemoryPolicy(
            profileMemory = profileMemory,
            ledger        = sharedActionLedger,
        )
        memoryHandler = MemoryActionHandler(profileMemory, memoryPolicy)
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
            contactLookup       = sharedContactLookup,  // A2: shared instance
            reminderRepository  = reminderRepo,
            settings            = settings
        )

        // User-visible Proactivity settings (master toggle, categories,
        // quiet hours, sensitivity, interruption mode, cooldown).  We
        // reuse the process-wide instance from JarvisApp so the UI and
        // the runtime see the same snapshot.
        proactivityRepository = com.jarvis.assistant.JarvisApp.proactivitySettings
        val proactivityGate = com.jarvis.assistant.proactive.settings.ProactivityGate(
            settingsProvider         = { proactivityRepository.snapshot() },
            msSinceLastGlobalSurface = { sharedCooldownStore.msSinceLastGlobalSurface() },
        )

        // ── Ambient Intelligence emitter ─────────────────────────────────────
        val ambientEventStore = com.jarvis.assistant.ambient.AmbientEventStore(
            dao              = db.ambientEventDao(),
            scope            = scope,
            settingsProvider = { JarvisApp.ambientSettings.snapshot() },
        )
        val routineLearningEngine = com.jarvis.assistant.ambient.RoutineLearningEngine(
            store            = ambientEventStore,
            dao              = db.routinePatternDao(),
            scope            = scope,
            settingsProvider = { JarvisApp.ambientSettings.snapshot() },
        )
        ambientEmitter = com.jarvis.assistant.ambient.AmbientProactiveEventEmitter(
            eventStore       = ambientEventStore,
            learningEngine   = routineLearningEngine,
            settingsProvider = { JarvisApp.ambientSettings.snapshot() },
        )
        // Mirror to the app-wide singleton so the Settings UI reads the same instance.
        JarvisApp.ambientEmitter = ambientEmitter

        // Proactive awareness engine.  Quiet hours enabled in production so
        // nightly suggestions stay suppressed unless the event is critical
        // (low battery, imminent reminder); tests construct their own config.
        // Note: the user-facing quiet-hours toggle is applied by
        // [ProactivityGate]; the values here are the engine-internal
        // hardcoded defaults that act as a baseline.  Sensitivity scaling
        // is applied to passiveThreshold / activeThreshold below.
        val initialSettings = proactivityRepository.snapshot()
        val sensitivityMult  = initialSettings.sensitivity.thresholdMultiplier
        proactiveEngine = ProactiveEngine(
            config               = ProactiveConfig(
                quietHoursStartHour = 22,
                quietHoursEndHour   = 7,
                passiveThreshold    = (0.55f * sensitivityMult).coerceIn(0.20f, 0.95f),
                activeThreshold     = (0.80f * sensitivityMult).coerceIn(0.40f, 0.99f),
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
                context               = context,
                ttsEngine             = ttsEngine,
                onPassiveAction       = { action -> Log.d(TAG, "Proactive passive: ${action.title}") },
                voiceResponseEnabled  = { settings.voiceResponse },
                executive             = executiveController,                // Tier C1
                modeProvider          = { modeController.current },         // Tier C2
                proactivityGate       = proactivityGate,                    // user policy
                lastUserInteractionMs = { speechStateSource.lastInteractionMs() },
            ),
            isDrivingProvider    = { drivingModeManager.isDriving },
            cooldownDao          = db.proactiveCooldownDao(),
            traceStore           = DecisionTraceStore(db.decisionTraceDao()),
            recentEventBuffer    = recentEventBuffer,
            knownSsidStore       = knownSsidStore,
            sharedCooldownStore  = sharedCooldownStore,
            actionLedger         = sharedActionLedger,
            routineSynthesizer        = routineSynthesizer,
            ambientContextProvider    = { ambientEmitter.currentContext },
        )

        // ── Scheduled reminders (30m + 10m pre-warnings) ──────────────────
        // The bridge constructs SpeakAction / PassiveAction directly and
        // hands it to a fresh TtsProactiveDispatcher wired with the same
        // ProactivityGate as the main engine, so every user policy is
        // honoured (master switch, quiet hours, interruption mode,
        // category, cooldown).  No direct TTS — by design.
        val scheduledRemindersDispatcher = TtsProactiveDispatcher(
            context               = context,
            ttsEngine             = ttsEngine,
            voiceResponseEnabled  = { settings.voiceResponse },
            executive             = executiveController,
            modeProvider          = { modeController.current },
            proactivityGate       = proactivityGate,
            lastUserInteractionMs = { speechStateSource.lastInteractionMs() },
        )
        scheduledReminderBridge = com.jarvis.assistant.proactive.scheduled
            .ScheduledReminderDispatchBridge(
                dispatcher       = scheduledRemindersDispatcher,
                settingsProvider = { JarvisApp.scheduledReminderSettings.snapshot() },
                lastInteractionMs = { speechStateSource.lastInteractionMs() },
            )
        scheduledReminderEngine = com.jarvis.assistant.proactive.scheduled
            .ScheduledReminderEngine(
                eventSink = { ev -> scheduledReminderBridge?.handle(ev) },
                sources = listOf(
                    com.jarvis.assistant.proactive.scheduled
                        .CalendarReminderSource(context),
                    com.jarvis.assistant.proactive.scheduled
                        .TodoistReminderSource(
                            clientProvider = {
                                val token = settings.todoistApiToken
                                if (token.isBlank()) null
                                else com.jarvis.assistant.todoist
                                    .TodoistClient(tokenProvider = { token })
                            },
                        ),
                    com.jarvis.assistant.proactive.scheduled
                        .LocalReminderSource(reminderRepo),
                ),
                settingsProvider = { JarvisApp.scheduledReminderSettings.snapshot() },
            )

        // Conversational follow-up engine
        lastSeenTracker    = LastSeenTracker(context)
        followUpRepo       = FollowUpRepository(db.pendingFollowUpDao())
        convProactiveEngine = ConversationalProactiveEngine(
            followUpRepo  = followUpRepo,
            lastSeen      = lastSeenTracker,
            onCheckIn     = ::dispatchConversationalCheckIn,
            cooldownStore = sharedCooldownStore,
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
            voiceResponseEnabled = { settings.voiceResponse },
            executive            = executiveController,                 // Tier C1
            modeProvider         = { modeController.current }           // Tier C2
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

        // ── Phone-reliability sprint: prove the remote-systems-disabled policy ──
        // OpenClaw and Hermes default to OFF.  Surface the active state at
        // startup so a logcat audit immediately confirms whether remote
        // routing is in the path for this session.
        if (!settings.openClawEnabled) Log.i(TAG, "[OPENCLAW_DISABLED] default policy — phone-first")
        else                            Log.i(TAG, "[OPENCLAW_ENABLED] user opted in")
        if (!settings.hermesEnabled)   Log.i(TAG, "[HERMES_DISABLED] default policy — phone-first")
        else                            Log.i(TAG, "[HERMES_ENABLED] user opted in")

        // ── Voice trust startup — prevents owner lockout ──────────────────
        // The user pressed Start (or the service was launched).  Default
        // policy: assume the owner is talking.  Voice identification
        // becomes an UPGRADE signal (VOICE_MATCHED) — never a block.
        if (settings.voiceAssumeOwnerOnStart) {
            Log.i(TAG, "[OWNER_ASSUMED_ON_START] " +
                "strictMode=${settings.voiceStrictMode} " +
                "askWho=${settings.voiceAskWhoWhenUncertain}")
            Log.i(TAG, "[VOICE_TRUST_STATE_SET] OWNER_ASSUMED")
        } else {
            Log.i(TAG, "[VOICE_TRUST_STATE_SET] strict_no_assume — " +
                "user opted out of safe defaults")
        }

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
        // Pre-warm the active LLM provider's TLS connection so the first
        // turn after a cold start doesn't pay the DNS + handshake cost
        // (~150–400 ms on a cold radio).  Best-effort, never blocks.
        scope.launch(Dispatchers.IO) {
            try { llmRouter.prewarmActiveProvider() }
            catch (e: Exception) { Log.d(TAG, "LLM prewarm failed: ${e.message}") }
        }
        // Pre-warm the OpenClaw/Hermes endpoint in parallel so the first
        // routed query doesn't pay the connection setup cost.
        scope.launch(Dispatchers.IO) {
            try {
                if (openClawRouter.shouldRoute()) {
                    val settings = openClawRepo.snapshot()
                    val url = com.jarvis.assistant.remote.openclaw.OpenClawConnectionBuilder
                        .buildChatEndpoint(settings)
                    // Fire a HEAD request — establishes DNS + TLS, discards the body.
                    val req = okhttp3.Request.Builder().url(url).head().build()
                    com.jarvis.assistant.llm.NetworkClient.http
                        .newCall(req).execute().close()
                    Log.d(TAG, "OpenClaw prewarm OK: $url")
                }
            } catch (e: Exception) {
                Log.d(TAG, "OpenClaw prewarm failed (non-fatal): ${e.message}")
            }
        }

        // Rehydrate action-class suppressions from persisted dislike facts.
        // [ActionLedger] already restores its own SharedPreferences snapshot on
        // construct; this second pass guarantees any dislike written before a
        // previous process crash still reaches the in-memory suppressed set.
        scope.launch(Dispatchers.IO) {
            try { memoryPolicy.hydrateSuppressionsFromMemory() }
            catch (e: Exception) { Log.w(TAG, "MemoryPolicy hydrate failed", e) }
        }

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

        // Event bus adapters — must attach AFTER callMonitor.start() so the
        // TelephonyEventAdapter's flow subscription sees events from tick zero.
        eventAdapters
            .add(TelephonyEventAdapter(callMonitor, scope))
            .add(DrivingModeEventAdapter(drivingModeManager))
            .add(BatteryEventAdapter(context))
            .add(NetworkEventAdapter(context))
            .add(ForegroundAppEventAdapter(context))
            .add(ProximityEventAdapter(context))
            .add(HomeAssistantEventAdapter(
                clientProvider = {
                    val base = settings.haBaseUrl
                    val token = settings.haApiToken
                    if (base.isBlank() || token.isBlank()) null
                    else com.jarvis.assistant.tools.smart.HomeAssistantClient(base, token)
                },
                wsClientProvider = {
                    val base = settings.haBaseUrl
                    val token = settings.haApiToken
                    if (base.isBlank() || token.isBlank()) null
                    else com.jarvis.assistant.tools.smart.HomeAssistantWebSocketClient(base, token)
                },
            ))
            .attachAll()
        recentEventBuffer.attach()
        routineSynthesizer.attach()
        expectationStore.attach()
        agentContextProvider.attach()
        cloudSyncService.start()

        ambientEmitter.start()         // Ambient Intelligence
        proactiveEngine.start()       // Proactive awareness polling
        scheduledReminderEngine?.start(scope)  // Scheduled reminders 30/10m
        convProactiveEngine.start()   // Conversational follow-up polling
        brainEngine.start()           // Behavioural learning system
        if (openClawRepo.snapshot().isFullyConfigured) openClawNode.start()  // OpenClaw node (inbound)
        drivingModeManager.start(context)  // Driving mode detection
        // Tier-B ambient context + mode auto-switch.  Aggregator polls every 5 s
        // and pushes each snapshot into ModeController so DRIVING / NIGHT modes
        // engage without manual user intervention.  Both pieces no-op when
        // their respective feature flags are off.
        ambientContext.start(scope)
        scope.launch {
            ambientContext.snapshot.collect { snap -> modeController.consumeContext(snap) }
        }
        followUpCoordinator.restorePersistedFlow()  // Restore any flow that survived a restart
        ttsEngine.applyVoice(settings.ttsVoiceName)

        // ── STT post-processing wire-up ──────────────────────────────────────
        // Route every SpeechCapture result through the N-best corrector so
        // candidates like "send a what's up to mic" become "send a WhatsApp to
        // Mike" before any tool matcher / LLM call sees them.
        speechCapture.setNbestSelector { candidates, confidences ->
            val r = transcriptCorrector.correct(candidates, confidences)
            lastCorrectorScore = r?.score ?: 0
            r?.text ?: candidates.firstOrNull() ?: ""
        }
        // Pre-load runtime vocabulary off the main thread.  Contacts + installed
        // app labels go in; HA rooms get added by the HA client later when its
        // entity cache warms up.
        scope.launch(Dispatchers.IO) {
            try {
                val contactNames = sttContactLookup.allDisplayNames()
                val appLabels    = try {
                    AppResolver(context, AppAliasStore(context)).launcherLabels()
                } catch (e: Exception) {
                    Log.w(TAG, "STT vocab: AppResolver scan failed: ${e.message}")
                    emptySet()
                }
                VocabularyBiaser.setRuntimeVocab(
                    contacts = contactNames,
                    apps     = appLabels
                )
                Log.d(TAG, "[STT_VOCAB_LOADED] contacts=${contactNames.size} apps=${appLabels.size}")

                // Pull HA rooms/devices into the biaser on the same thread.
                // Safe when HA isn't configured — the refresher early-outs.
                try {
                    val base  = settings.haBaseUrl
                    val token = settings.haApiToken
                    val haClient = if (base.isBlank() || token.isBlank()) null
                        else com.jarvis.assistant.tools.smart.HomeAssistantClient(base, token)
                    com.jarvis.assistant.audio.stt.HomeAssistantVocabRefresher.refresh(haClient)
                } catch (e: Exception) {
                    Log.w(TAG, "HA vocab refresh failed: ${e.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "STT vocab population failed: ${e.message}")
            }
        }

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
     * Drive the OpenClaw node WebSocket from the Settings UI toggle.
     *
     * When [enabled] is true and the OpenClaw settings are fully configured
     * (host + port + auth token), the persistent gateway WebSocket is
     * started — same flow as at service onCreate.  When false, the
     * WebSocket is closed and the node's status flips to DISABLED.
     *
     * Without this, the "Register as OpenClaw node" toggle in Settings
     * only updated the persisted boolean — the actual WebSocket was only
     * started once at service onCreate, gated on `isFullyConfigured`.
     * Toggling after that did nothing visible (and the user reported
     * "There's nothing even in the logs on OpenClaw to say anything's
     * even tried to connect").
     */
    fun setOpenClawNodeEnabled(enabled: Boolean) {
        Log.d(TAG, "[OPENCLAW_NODE_TOGGLE_RUNTIME] enabled=$enabled")
        if (enabled) {
            if (openClawRepo.snapshot().isFullyConfigured) {
                openClawNode.start()
            } else {
                Log.w(TAG, "[OPENCLAW_NODE_TOGGLE_RUNTIME] settings not fully " +
                    "configured — start request ignored")
            }
        } else {
            openClawNode.stop()
        }
    }

    // ── TtsResponseController delegation ──────────────────────────────
    // Diagnostics + sample-voice TTS extracted to a focused controller
    // (see runtime/controllers/TtsResponseController.kt and
    // docs/architecture/runtime-split.md).  JarvisRuntime keeps the
    // public methods as a façade so every existing call site
    // (Settings UI, ViewModel, service) is untouched.  Lazy because
    // `scope` is a `private val`; the controller can be instantiated
    // at first access without ordering games.
    private val ttsResponseController by lazy {
        com.jarvis.assistant.runtime.controllers.TtsResponseController(
            ttsEngine    = ttsEngine,
            settings     = settings,
            scope        = scope,
            applyVoice   = ::applyVoice,
            suppressWake = ::suppressWakeDetection,
            restoreWake  = ::restoreWakeDetection,
        )
    }

    /**
     * Switch to [voiceName] and speak a short test phrase so the user can audition it.
     * Suppresses and restores wake detection automatically.
     *
     * Delegates to [TtsResponseController.testSpeak].
     */
    fun testSpeak(voiceName: String) = ttsResponseController.testSpeak(voiceName)

    /**
     * Dispatch a synthetic SpeakAction through the live ProactivityGate
     * and report back which gate verdict (Allow / Downgrade / Suppress)
     * would fire right now.  Drives the Settings "Test normal
     * proactivity decision" button.
     *
     * Delegates to [TtsResponseController.dispatchProactivityGateTest].
     */
    fun dispatchProactivityGateTest(onResult: (String) -> Unit) =
        ttsResponseController.dispatchProactivityGateTest(onResult)

    /**
     * Speak a fixed sample line immediately, bypassing every Proactivity
     * gate.  Drives the Settings "Test spoken message now" button.
     *
     * Delegates to [TtsResponseController.speakProactivityTest].
     */
    fun speakProactivityTest(
        text: String = "Proactivity test — if you can hear this, voice output is working.",
        onResult: (failureReason: String?) -> Unit = {},
    ) = ttsResponseController.speakProactivityTest(text, onResult)

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

    private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)

    fun stop() {
        // Idempotent — JarvisService.onDestroy() and ACTION_STOP both end up
        // calling stop(), and the OS may interleave them when a stop request
        // races a process death.  Without this guard ttsEngine.shutdown(),
        // bluetoothSco.release() and friends ran twice and a second
        // scope.cancel() races the in-flight flushSessionToDb() coroutine.
        //
        // compareAndSet rather than a @Volatile read+write so two threads
        // entering stop() at the same instant only let one through — the
        // earlier @Volatile version had a check-then-act window where both
        // could pass the guard before either flipped the flag.
        if (!stopped.compareAndSet(false, true)) {
            Log.d(TAG, "Stop ignored — already stopped")
            return
        }
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
        cloudSyncService.stop()
        agentContextProvider.detach()
        expectationStore.detach()
        routineSynthesizer.detach()
        recentEventBuffer.detach()
        eventAdapters.detachAll()       // Unwire bus adapters before callMonitor.stop()
        callMonitor.stop()              // Phase 6
        openClawNode.stop()             // OpenClaw node (inbound)
        ambientEmitter.stop()            // Ambient Intelligence
        proactiveEngine.stop()          // Proactive awareness
        scheduledReminderEngine?.stop() // Scheduled reminder lanes
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
     * Always uses the device's built-in microphone — SCO is NOT activated here.
     * Activating SCO (HFP/HSP Bluetooth phone-call channel) before the detector
     * causes ERROR_AUDIO on most TWS earbuds and breaks audio routing. SCO is
     * only activated in the pipeline after wake fires, for the active listening
     * and TTS phases. A new [wakeWordSetupJob] is created on every call —
     * any previous in-flight setup is cancelled first.
     */
    private fun startWakeWordDetection() {
        wakeDetector?.stop()
        wakeDetector = null
        wakeWordSetupJob?.cancel()
        wakeWordSetupJob = scope.launch {
            // Do NOT activate SCO here. SCO is the HFP phone-call audio channel
            // (8 kHz narrowband). Activating it during wake-word detection routes
            // the microphone through Bluetooth SCO instead of the device's built-in
            // mic, which causes ERROR_AUDIO on most TWS earbuds and many Android
            // devices. Wake detection always uses the built-in mic — this is how
            // every major voice assistant (Google, Alexa, Siri) works. SCO is
            // activated in the pipeline after wake fires (see connect() call below).
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

        // Tactile confirmation that Jarvis heard you — fired before the
        // pipeline runs so it lands within the same frame as detection
        // rather than after the 800 ms mic-handoff delay.  Failure is
        // silent because Vibrator is optional (some tablets, watch screens).
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(android.os.VibratorManager::class.java))
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(android.os.Vibrator::class.java)
            }
            if (vib?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(
                        android.os.VibrationEffect.createOneShot(
                            20L,
                            android.os.VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(20L)
                }
            }
        }

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

                // Mic handoff: SpeechRecognizer.destroy() is asynchronous — the old
                // wake-detector session may not have released the mic yet.  We no
                // longer sleep blindly here; SpeechCapture.listen() retries on
                // ERROR_AUDIO (up to 3 times with 150/150/200 ms gaps) so the
                // expected wait is 0 ms when the mic is free, or ~150 ms on the
                // first retry — much less than the previous flat 500 ms delay.
                val micHandoffStart = android.os.SystemClock.elapsedRealtime()
                Log.d(TAG, "[MIC_HANDOFF_START] t=+0ms (relative to mic handoff)")

                // Phase 4: connect SCO if a headset is present
                // Phase 5 note: audio focus is NOT requested here.  Holding focus
                // during passive listening causes Spotify to pause permanently.
                // Focus is requested just before TTS starts and released immediately
                // after — see speakAndRecord() and the streamAndSpeak() paths.
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

                // Fire-and-forget: chime starts playing immediately and the mic
                // opens in parallel.  The chime is 300 ms of audio; the thread
                // inside startChimeAsync() releases the AudioTrack after 350 ms.
                // This saves ~600 ms vs the old suspending playChime() call.
                ttsEngine.startChimeAsync()
                Log.d(TAG, "[CHIME_ASYNC] chime started, mic opening in parallel " +
                    "+${android.os.SystemClock.elapsedRealtime() - micHandoffStart}ms")

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

                // Morning briefing on first wake of the day (before 10 am).
                // Falls back to a random ack for all other activations.
                // Skipped during first-run setup / voice enrollment.
                if (!sessionSpeaker.awaitingOwnerName && !sessionSpeaker.awaitingVoiceEnrollmentSample) {
                    val brief = tryMorningBriefing()
                    ttsEngine.speak(brief ?: WakeAcknowledgements.random())
                }

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
                    // ── Latency session start ─────────────────────────────────
                    // Reset on every utterance so STT_COMPLETE/total reflects
                    // the CURRENT request, not the cumulative time since the
                    // wake-word ages ago.  Without this, mid-conversation
                    // utterances reported "total=12000ms" because the timer
                    // had never been restarted.
                    LatencyTracker.pipelineStart()
                    Log.d(TAG, "[LATENCY_SESSION_START] " +
                        "utterance_clock_started, prior_state=conversation_loop")
                    Log.d(TAG, "[STT_BEGIN] forceOffline=${!contextEngine.isOnline()}")
                    LatencyTracker.mark("STT_START")
                    // When offline, force the on-device recognizer (API 31+) so STT
                    // keeps working without network. On older APIs this flag is a
                    // no-op — the default intent already sets EXTRA_PREFER_OFFLINE.
                    val transcript  = speechCapture.listen(
                        onReady = {
                            scope.launch {
                                delay(100)
                                activeCapture?.start()
                            }
                        },
                        forceOffline = !contextEngine.isOnline()
                    )
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
                    // [TRANSCRIPT_RAW] / [TRANSCRIPT_NORMALIZED] are the
                    // canonical grep targets for routing audits — emit them
                    // exactly once per turn, right after STT_COMPLETE.
                    Log.d(TAG, "[TRANSCRIPT_RAW] \"$transcript\"")
                    val transcriptNormalisedLog = com.jarvis.assistant.voice.routing
                        .TranscriptNormalizer.normalizeForMatching(transcript)
                    Log.d(TAG, "[TRANSCRIPT_NORMALIZED] \"$transcriptNormalisedLog\"")

                    // ── Confirmation-first intercept ─────────────────────────
                    // A pending "are you sure?" from the previous turn gets
                    // first refusal on this utterance, BEFORE AttentionGate or
                    // any routing.  Without this, a short reply like "yes" /
                    // "no" gets dropped by the gate (zero command pattern, no
                    // tool match) and the user perceives Jarvis as stalled.
                    when (val v = confirmationGate.consume(transcript)) {
                        is ConfirmationGate.Verdict.Affirmed -> {
                            Log.d(TAG, "[CONFIRMATION_ACCEPTED] tool=${v.pending.toolName} " +
                                "transcript=\"$transcript\"")
                            val stashedTool = toolRegistry.findByName(v.pending.toolName)
                            if (stashedTool != null) {
                                val r = toolDispatcher.dispatch(
                                    stashedTool, v.pending.input, sessionSpeaker,
                                    v.pending.originatingTranscript, skipConfirmation = true
                                )
                                Log.d(TAG, "[CONFIRMATION_EXECUTED] tool=${v.pending.toolName} " +
                                    "result=${r::class.simpleName}")
                                when (r) {
                                    is ToolDispatcher.DispatchResult.Done ->
                                        if (r.spokenFeedback.isNotBlank()) speakAndRecord(r.spokenFeedback)
                                    is ToolDispatcher.DispatchResult.Failed -> speakAndRecord(r.message)
                                    is ToolDispatcher.DispatchResult.Denied -> speakAndRecord(r.message)
                                    is ToolDispatcher.DispatchResult.SilentExit,
                                    is ToolDispatcher.DispatchResult.AugmentedLlm,
                                    is ToolDispatcher.DispatchResult.LlmFollowUp,
                                    is ToolDispatcher.DispatchResult.NeedsConfirmation -> Unit
                                }
                            } else {
                                speakAndRecord("That tool's gone — skipping it.")
                            }
                            machine.transition(JarvisState.Listening)
                            syncState(JarvisState.Listening)
                            continue
                        }
                        is ConfirmationGate.Verdict.Declined -> {
                            Log.d(TAG, "[CONFIRMATION_CANCELLED] tool=${v.pending.toolName}")
                            speakAndRecord("Dropped it.")
                            machine.transition(JarvisState.Listening)
                            syncState(JarvisState.Listening)
                            continue
                        }
                        ConfirmationGate.Verdict.None -> Unit
                    }

                    // ── Messaging follow-up intercept ────────────────────────
                    // If a previous turn parked a PendingMessageIntent ("send a
                    // WhatsApp" → asked for recipient+body), the user's next
                    // utterance is the answer to that question.  Merge slots
                    // and run the pipeline directly — never AttentionGate
                    // it, never OpenClaw it.
                    pendingMessageIntent?.let { pending ->
                        val followUpStartMs = System.currentTimeMillis()
                        Log.d(TAG, "[FOLLOWUP_RECEIVED] transcript=\"$transcript\" " +
                            "channel=${pending.channel} +0ms")
                        if (pending.isExpired()) {
                            Log.d(TAG, "[FOLLOWUP_EXPIRED] pending dropped")
                            pendingMessageIntent = null
                            // Fall through to normal routing.
                            return@let
                        }
                        Log.d(TAG, "[FOLLOWUP_PENDING_INTENT_FOUND] channel=${pending.channel}")
                        Log.d(TAG, "[FOLLOWUP_MERGE_START]")
                        val merged = com.jarvis.assistant.tools.device.messaging
                            .PendingMessageIntent.merge(pending, transcript)
                        pendingMessageIntent = null   // consume now; merged drives the rest

                        if (!merged.isReady) {
                            // Still missing something — ask again, re-park.
                            Log.d(TAG, "[FOLLOWUP_STILL_INCOMPLETE] recipient=\"${merged.recipient}\" body=\"${merged.body}\"")
                            val askAgain = when {
                                merged.recipient.isBlank() -> "Who should I send it to?"
                                else                        -> "What should it say?"
                            }
                            pendingMessageIntent = merged.copy(
                                createdMs   = System.currentTimeMillis(),
                                expiresAtMs = System.currentTimeMillis() +
                                    com.jarvis.assistant.tools.device.messaging
                                        .PendingMessageIntent.TTL_MS
                            )
                            speakAndRecord(askAgain)
                            machine.transition(JarvisState.Listening)
                            syncState(JarvisState.Listening)
                            continue
                        }

                        // All slots filled — dispatch through the matching tool
                        // so the pipeline + adapter run with the correct
                        // execution + log markers.  Tool selection follows the
                        // channel stored on the pending intent.
                        val toolName = when (merged.channel) {
                            com.jarvis.assistant.tools.device.MessageIntentParser
                                .Channel.WHATSAPP -> "whatsapp_message"
                            com.jarvis.assistant.tools.device.MessageIntentParser
                                .Channel.SMS      -> "send_sms"
                        }
                        val tool  = toolRegistry.findByName(toolName)
                        if (tool == null) {
                            Log.w(TAG, "[FOLLOWUP_TOOL_MISSING] tool=$toolName " +
                                "— cannot complete merged message")
                            speakAndRecord("I can't send that right now.")
                            machine.transition(JarvisState.Listening)
                            syncState(JarvisState.Listening)
                            continue
                        }
                        val input = com.jarvis.assistant.tools.framework.ToolInput(
                            transcript,
                            mapOf("name" to merged.recipient, "message" to merged.body)
                        )
                        val dispatchStartMs = System.currentTimeMillis()
                        Log.d(TAG, "[FOLLOWUP_DISPATCH_START] tool=$toolName " +
                            "+${dispatchStartMs - followUpStartMs}ms")
                        val r = toolDispatcher.dispatch(
                            tool, input, sessionSpeaker, transcript,
                            skipConfirmation = true,        // user already gave consent
                            confidenceTier   = com.jarvis.assistant.audio.stt
                                .TranscriptCorrector.ConfidenceTier.HIGH
                        )
                        Log.d(TAG, "[FOLLOWUP_DISPATCH_DONE] tool=$toolName " +
                            "+${System.currentTimeMillis() - followUpStartMs}ms " +
                            "result=${r::class.simpleName}")
                        when (r) {
                            is ToolDispatcher.DispatchResult.Done ->
                                if (r.spokenFeedback.isNotBlank()) speakAndRecord(r.spokenFeedback)
                            is ToolDispatcher.DispatchResult.Failed -> speakAndRecord(r.message)
                            is ToolDispatcher.DispatchResult.Denied -> speakAndRecord(r.message)
                            else -> Unit
                        }
                        machine.transition(JarvisState.Listening)
                        syncState(JarvisState.Listening)
                        continue
                    }

                    // ── Todoist follow-up intercept ──────────────────────────
                    // A parked PendingTodoistTask from the previous turn (e.g.
                    // we asked "When should I remind you?") wins over every
                    // other route — the user's reply is the answer to that
                    // question.  Cleared on execute / decline / expiry.
                    pendingTodoistTask?.let { pending ->
                        if (pending.isExpired()) {
                            Log.d(TAG, "[TODOIST_PENDING_EXPIRED] " +
                                "content=\"${pending.content}\" slot=${pending.awaitingSlot}")
                            Log.d(TAG, "[TODOIST_FOLLOWUP_EXPIRED] dropped")
                            pendingTodoistTask = null
                            return@let
                        }
                        Log.d(TAG, "[TODOIST_FOLLOWUP_RECEIVED] slot=${pending.awaitingSlot}")
                        speechStateSource.recordUserInteraction()
                        DeviceStateStore.update { copy(lastUserUtterance = transcript) }
                        scope.launch(Dispatchers.IO) {
                            memoryWriter.writeTurn(sessionId, "user", transcript)
                        }
                        val action = todoistReminderRouter.handleFollowUp(pending, transcript)
                        pendingTodoistTask = null
                        when (action) {
                            is com.jarvis.assistant.todoist.TodoistReminderRouter.RouterAction.Speak ->
                                speakAndRecord(action.text)
                            is com.jarvis.assistant.todoist.TodoistReminderRouter.RouterAction.ParkPending -> {
                                pendingTodoistTask = action.pending
                                speakAndRecord(action.askPrompt)
                            }
                            is com.jarvis.assistant.todoist.TodoistReminderRouter.RouterAction.NotApplicable ->
                                Unit
                        }
                        machine.transition(JarvisState.Listening)
                        syncState(JarvisState.Listening)
                        continue
                    }

                    // ── Todoist conversational edit intercept ───────────────
                    // "move that to tomorrow", "make that urgent",
                    // "delete that", "actually 9pm" — referring to the
                    // most recently created task.  Runs BEFORE the fresh-
                    // reminder check because edits don't start with a
                    // reminder verb.
                    if (com.jarvis.assistant.todoist.edit
                            .ConversationalEditParser.looksLikeEdit(transcript)
                    ) {
                        Log.d(TAG, "[TODOIST_EDIT_DETECTED] transcript=\"${transcript.take(60)}\"")
                        speechStateSource.recordUserInteraction()
                        DeviceStateStore.update { copy(lastUserUtterance = transcript) }
                        scope.launch(Dispatchers.IO) {
                            memoryWriter.writeTurn(sessionId, "user", transcript)
                        }
                        val editAction = todoistReminderRouter.handleEdit(transcript)
                        when (editAction) {
                            is com.jarvis.assistant.todoist.TodoistReminderRouter.RouterAction.Speak -> {
                                speakAndRecord(editAction.text)
                                machine.transition(JarvisState.Listening)
                                syncState(JarvisState.Listening)
                                continue
                            }
                            is com.jarvis.assistant.todoist.TodoistReminderRouter.RouterAction.ParkPending,
                            is com.jarvis.assistant.todoist.TodoistReminderRouter.RouterAction.NotApplicable ->
                                Unit  // fall through to fresh / other routing
                        }
                    }

                    // ── Contextual follow-up intercept ───────────────────────
                    // Resolves bare follow-ups against the last action:
                    //   "turn off"          after flashlight-on
                    //   "show me the selfie" after camera capture
                    //   "do that again"     after any action
                    // Runs BEFORE InstantCommandRouter so "turn off"
                    // (with no prior verb) doesn't try to match a raw
                    // smart-home intent with an empty target.
                    Log.d(TAG, "[ROUTER_ORDER_CHECK] " +
                        "contextual_followup_before_instant_router")
                    if (com.jarvis.assistant.runtime.context
                            .ContextualFollowupParser.looksLikeFollowup(transcript)
                    ) {
                        val resolution = com.jarvis.assistant.runtime.context
                            .ContextualFollowupResolver.resolve(transcript, recentActionContext)
                        when (resolution) {
                            is com.jarvis.assistant.runtime.context
                                .ContextualFollowupResolver.Resolution.Dispatch -> {
                                Log.d(TAG, "[CONTEXT_FOLLOWUP_DISPATCH] " +
                                    "tool=${resolution.toolName} " +
                                    "followup=${resolution.originatingFollowup::class.simpleName}")
                                val tool = toolRegistry.findByName(resolution.toolName)
                                if (tool != null) {
                                    speechStateSource.recordUserInteraction()
                                    DeviceStateStore.update {
                                        copy(lastUserUtterance = transcript)
                                    }
                                    val input = com.jarvis.assistant.tools.framework
                                        .ToolInput(transcript, resolution.params)
                                    val r = toolDispatcher.dispatch(
                                        tool, input, sessionSpeaker, transcript,
                                        skipConfirmation = true,
                                        confidenceTier = com.jarvis.assistant.audio.stt
                                            .TranscriptCorrector.ConfidenceTier.HIGH,
                                    )
                                    when (r) {
                                        is ToolDispatcher.DispatchResult.Done ->
                                            if (r.spokenFeedback.isNotBlank())
                                                speakAndRecord(r.spokenFeedback)
                                        is ToolDispatcher.DispatchResult.Failed ->
                                            speakAndRecord(r.message)
                                        is ToolDispatcher.DispatchResult.Denied ->
                                            speakAndRecord(r.message)
                                        else -> Unit
                                    }
                                    attentionGate.extendActiveWindow()
                                    machine.transition(JarvisState.Listening)
                                    syncState(JarvisState.Listening)
                                    continue
                                }
                                Log.w(TAG, "[CONTEXT_FOLLOWUP_TOOL_MISSING] " +
                                    "tool=${resolution.toolName} — falling through")
                            }
                            is com.jarvis.assistant.runtime.context
                                .ContextualFollowupResolver.Resolution.Speak -> {
                                speakAndRecord(resolution.text)
                                machine.transition(JarvisState.Listening)
                                syncState(JarvisState.Listening)
                                continue
                            }
                            is com.jarvis.assistant.runtime.context
                                .ContextualFollowupResolver.Resolution.NotApplicable ->
                                Unit   // fall through
                        }
                    }

                    // ── Todoist complete-bulk / by-name intercept ─────────────
                    // "complete those tasks", "mark today's tasks done",
                    // "complete pick up Mike".  Runs BEFORE the fresh-
                    // reminder path because "complete X" can otherwise
                    // be mis-parsed as a task-create intent.  The router
                    // refuses ambiguous by-name matches and returns
                    // NotApplicable for "complete that" (which the
                    // existing handleEdit path owns via recentTaskContext).
                    run {
                        val lower = transcript.lowercase()
                        val verbHit = Regex(
                            """^(?:please\s+)?(?:complete|mark|tick|cross\s+off|finish|close)\b""",
                            RegexOption.IGNORE_CASE,
                        ).containsMatchIn(lower)
                        if (verbHit) {
                            val bulkAction = todoistReminderRouter.handleCompleteRequest(transcript)
                            when (bulkAction) {
                                is com.jarvis.assistant.todoist.TodoistReminderRouter.RouterAction.Speak -> {
                                    Log.d(TAG, "[TODOIST_COMPLETE_DISPATCHED]")
                                    speechStateSource.recordUserInteraction()
                                    DeviceStateStore.update { copy(lastUserUtterance = transcript) }
                                    scope.launch(Dispatchers.IO) {
                                        memoryWriter.writeTurn(sessionId, "user", transcript)
                                    }
                                    speakAndRecord(bulkAction.text)
                                    machine.transition(JarvisState.Listening)
                                    syncState(JarvisState.Listening)
                                    continue
                                }
                                is com.jarvis.assistant.todoist.TodoistReminderRouter.RouterAction.NotApplicable,
                                is com.jarvis.assistant.todoist.TodoistReminderRouter.RouterAction.ParkPending ->
                                    Unit  // fall through
                            }
                        }
                    }

                    // ── Todoist list-query intercept ─────────────────────────
                    // "what are my tasks", "show my todoist", "what's
                    // overdue", "today's tasks" — strictly read-only,
                    // local-only.  Without this the LLM fallback was
                    // answering "I don't have a task list feature" even
                    // though Todoist was connected.  See GH #36.
                    if (com.jarvis.assistant.todoist.parse
                            .TodoistListQueryParser.looksLikeListQuery(transcript)
                    ) {
                        Log.d(TAG, "[TODOIST_LIST_QUERY_DETECTED] " +
                            "transcript=\"${transcript.take(60)}\"")
                        speechStateSource.recordUserInteraction()
                        DeviceStateStore.update { copy(lastUserUtterance = transcript) }
                        scope.launch(Dispatchers.IO) {
                            memoryWriter.writeTurn(sessionId, "user", transcript)
                        }
                        val listAction = todoistReminderRouter.handleListQuery(transcript)
                        when (listAction) {
                            is com.jarvis.assistant.todoist.TodoistReminderRouter.RouterAction.Speak -> {
                                speakAndRecord(listAction.text)
                                machine.transition(JarvisState.Listening)
                                syncState(JarvisState.Listening)
                                continue
                            }
                            is com.jarvis.assistant.todoist.TodoistReminderRouter.RouterAction.NotApplicable,
                            is com.jarvis.assistant.todoist.TodoistReminderRouter.RouterAction.ParkPending ->
                                Unit
                        }
                    }

                    // ── Todoist fresh-turn intercept ─────────────────────────
                    // If the utterance looks like a reminder/task command,
                    // route LOCALLY — no OpenClaw, no LLM, no memory.  The
                    // looksLikeReminderCommand short-circuit is the same
                    // predicate as the parser, so the cost of a false start
                    // is one cheap regex.
                    if (com.jarvis.assistant.todoist.parse
                            .ReminderIntentParser.looksLikeReminderCommand(transcript)
                    ) {
                        // Cancel any prior pending — a fresh reminder
                        // implicitly supersedes the old "what time?" /
                        // "which project?" wait.
                        pendingTodoistTask?.let {
                            Log.d(TAG, "[TODOIST_PENDING_CANCELLED] " +
                                "reason=new_reminder_intent content=\"${it.content}\"")
                            pendingTodoistTask = null
                        }
                        Log.d(TAG, "[ROUTER_ORDER_CHECK] todoist_fresh_before_openclaw")
                        Log.d(TAG, "[TODOIST_INTENT_DETECTED] transcript=\"${transcript.take(60)}\"")
                        speechStateSource.recordUserInteraction()
                        DeviceStateStore.update { copy(lastUserUtterance = transcript) }
                        scope.launch(Dispatchers.IO) {
                            memoryWriter.writeTurn(sessionId, "user", transcript)
                        }
                        val action = todoistReminderRouter.handleFresh(transcript)
                        when (action) {
                            is com.jarvis.assistant.todoist.TodoistReminderRouter.RouterAction.Speak -> {
                                speakAndRecord(action.text)
                                machine.transition(JarvisState.Listening)
                                syncState(JarvisState.Listening)
                                continue
                            }
                            is com.jarvis.assistant.todoist.TodoistReminderRouter.RouterAction.ParkPending -> {
                                pendingTodoistTask = action.pending
                                speakAndRecord(action.askPrompt)
                                machine.transition(JarvisState.Listening)
                                syncState(JarvisState.Listening)
                                continue
                            }
                            is com.jarvis.assistant.todoist.TodoistReminderRouter.RouterAction.NotApplicable -> {
                                // looksLikeReminderCommand triggered but the
                                // strict parser declined.  The user said
                                // a Todoist verb ("create a task", "add a
                                // todo", "remind me") but no content.
                                // Park a content-awaiting PendingTodoistTask
                                // so the NEXT utterance becomes the task
                                // content — without this fix the transcript
                                // fell through to OpenClaw and triggered
                                // [INVALID_REMOTE_ROUTE] (auto-issue #36).
                                //
                                // Detect the verb-only shape narrowly: the
                                // transcript looks like a reminder command
                                // AND is short enough that there's no body
                                // (≤ 6 tokens).  Longer rejections genuinely
                                // are not Todoist intents (e.g. "what time
                                // is the task due tomorrow").
                                val tokenCount = transcript.trim().split(Regex("\\s+")).size
                                if (tokenCount <= 6) {
                                    val isTaskVerb = Regex(
                                        """\b(?:add|create|make|new)\s+(?:a|the|an)?\s*(?:task|todo|to-?do)\b|^\s*todo\b""",
                                        RegexOption.IGNORE_CASE,
                                    ).containsMatchIn(transcript)
                                    val kind = if (isTaskVerb)
                                        com.jarvis.assistant.todoist.parse.ReminderIntentParser.Kind.TASK
                                    else
                                        com.jarvis.assistant.todoist.parse.ReminderIntentParser.Kind.REMINDER
                                    val nowMs = System.currentTimeMillis()
                                    pendingTodoistTask = com.jarvis.assistant.todoist.PendingTodoistTask(
                                        kind = kind,
                                        content = "",
                                        awaitingSlot = com.jarvis.assistant.todoist.PendingTodoistTask
                                            .AwaitingSlot.CONTENT,
                                        createdMs = nowMs,
                                        expiresAtMs = nowMs +
                                            com.jarvis.assistant.todoist.PendingTodoistTask.TTL_MS,
                                    )
                                    val prompt = if (isTaskVerb)
                                        "What's the task?"
                                    else
                                        "What should I remind you about?"
                                    Log.d(TAG, "[TODOIST_PENDING_PARKED] reason=missing_content " +
                                        "kind=$kind ttl_ms=${com.jarvis.assistant.todoist.PendingTodoistTask.TTL_MS}")
                                    speakAndRecord(prompt)
                                    machine.transition(JarvisState.Listening)
                                    syncState(JarvisState.Listening)
                                    continue
                                }
                                Log.d(TAG, "[TODOIST_FALLTHROUGH] " +
                                    "looksLike=true but parser rejected — escalating")
                            }
                        }
                    }

                    // ── InstantCommandRouter — the deterministic local gate ──
                    // Phone & device control must be instant and deterministic.
                    // OpenClaw is the deeper brain, not the first stop.  If
                    // the transcript matches an allowlisted local tool (time,
                    // battery, call, WhatsApp, alarm, smart-home, …) we
                    // execute IMMEDIATELY and return to Listening — no
                    // AttentionGate, no OpenClaw, no LLM, no memory retrieval.
                    //
                    // Anything else returns NoMatch and falls through to the
                    // existing LocalFirstRouter → OpenClaw → LLM cascade
                    // below.  Conversational state intercepts above
                    // (confirmation, messaging follow-up) already ran, so
                    // genuine stateful continuations are not bypassed.
                    val instantOnline = contextEngine.isOnline()
                    val instantResult = instantCommandRouter.route(transcript, instantOnline)
                    if (instantResult is com.jarvis.assistant.voice.routing
                            .InstantCommandRouter.InstantRouteResult.Match
                    ) {
                        Log.d(TAG, "[LOCAL_TOOL_EXECUTE] tool=${instantResult.tool.name} " +
                            "intent=${instantResult.intent}")
                        // Record the user turn (so memory + conversation
                        // history stay coherent) but do NOT retrieve memory
                        // or call the LLM — local commands answer from the
                        // device alone.
                        speechStateSource.recordUserInteraction()
                        lastSeenTracker.touchUserTurn()
                        DeviceStateStore.update { copy(lastUserUtterance = transcript) }
                        scope.launch(Dispatchers.IO) {
                            memoryWriter.writeTurn(sessionId, "user", transcript)
                        }

                        val dispatch = toolDispatcher.dispatch(
                            instantResult.tool,
                            instantResult.input,
                            sessionSpeaker,
                            transcript,
                            confidenceTier = com.jarvis.assistant.audio.stt
                                .TranscriptCorrector.ConfidenceTier.HIGH,
                        )
                        // ── Standardised LOCAL_* log markers ─────────────
                        // Replaces the older instant-router-specific tags
                        // with the sprint-spec contract so every local
                        // command has a uniform observability trail.
                        val localTStart = System.currentTimeMillis()
                        Log.d(TAG, "[LOCAL_ROUTE_MATCH] intent=${instantResult.intent} " +
                            "tool=${instantResult.tool.name}")
                        Log.d(TAG, "[LOCAL_TOOL_EXECUTE] tool=${instantResult.tool.name} " +
                            "intent=${instantResult.intent}")
                        // Consult the SessionContinuationPolicy BEFORE the
                        // dispatcher's silent/done flags decide for us.
                        // Volume / flashlight / time etc. must keep the
                        // session alive even when they return silent=true
                        // — the user is still in active conversation.
                        val continuation = com.jarvis.assistant.runtime.session
                            .SessionContinuationPolicy.decide(
                                toolName       = instantResult.tool.name,
                                transcript     = transcript.lowercase(),
                                ttsIsSpeaking  = machine.isIn<JarvisState.Speaking>(),
                            )
                        Log.d(TAG, "[SESSION_CONTINUE_DECISION] $continuation " +
                            "tool=${instantResult.tool.name}")
                        when (val r = dispatch) {
                            is ToolDispatcher.DispatchResult.Done -> {
                                if (r.spokenFeedback.isNotBlank()) {
                                    rememberFactReplyIfApplicable(
                                        r.hints?.toolName, r.spokenFeedback
                                    )
                                    speakAndRecord(r.spokenFeedback)
                                }
                            }
                            is ToolDispatcher.DispatchResult.Failed ->
                                speakAndRecord(r.message)
                            is ToolDispatcher.DispatchResult.Denied ->
                                speakAndRecord(r.message)
                            is ToolDispatcher.DispatchResult.NeedsConfirmation -> {
                                Log.d(TAG, "[CONFIRMATION_LISTEN_STARTED] " +
                                    "tool=${r.toolName} pending=${r.pendingId} " +
                                    "(via InstantCommandRouter)")
                                speakAndRecord(r.prompt)
                            }
                            is ToolDispatcher.DispatchResult.SilentExit -> {
                                // The tool returned silent=true.  Whether we
                                // ACTUALLY exit the session is determined by
                                // the policy above — volume/flashlight stay
                                // listening, only true session-ending tools
                                // (open_app, end_call, camera_capture) tear
                                // down.  Note: speakAndRecord is intentionally
                                // skipped here — silent=true means no speech.
                                if (continuation ==
                                    com.jarvis.assistant.runtime.session
                                        .SessionContinuationPolicy.Verdict.STOP_LISTENING
                                ) {
                                    Log.d(TAG, "[LISTENING_STOP_REQUESTED] " +
                                        "tool=${instantResult.tool.name}")
                                    Log.d(TAG, "[LISTENING_STOP_REASON] " +
                                        "session_ending_tool")
                                    closeSessionAsync()
                                    releaseResources()
                                    backToWakeWord()
                                    return@launch
                                }
                                // Default: silent + continue listening.
                                Log.d(TAG, "[LISTENING_RESTART_REQUESTED] " +
                                    "reason=silent_local_command tool=${instantResult.tool.name}")
                            }
                            is ToolDispatcher.DispatchResult.AugmentedLlm,
                            is ToolDispatcher.DispatchResult.LlmFollowUp -> {
                                // These dispatch outcomes are reserved for
                                // tools that intentionally need LLM help to
                                // shape their reply (e.g. ReadScreenTool).
                                // None of the INSTANT_TOOL_INTENTS produce
                                // them; if a future allowlisted tool does,
                                // it's a deliberate signal to fall through
                                // to the LLM path — which is fine.
                                Unit
                            }
                        }
                        // ── Latency + diagnostics trail ─────────────────
                        val totalMs = System.currentTimeMillis() - localTStart
                        val resultLabel = when (dispatch) {
                            is ToolDispatcher.DispatchResult.Done             -> "success"
                            is ToolDispatcher.DispatchResult.SilentExit       -> "success_silent"
                            is ToolDispatcher.DispatchResult.Failed           -> "failure(${dispatch.message.take(40)})"
                            is ToolDispatcher.DispatchResult.Denied           -> "denied(${dispatch.message.take(40)})"
                            is ToolDispatcher.DispatchResult.NeedsConfirmation -> "needs_confirmation"
                            else                                              -> "other(${dispatch::class.simpleName})"
                        }
                        if (resultLabel.startsWith("success")) {
                            Log.d(TAG, "[LOCAL_TOOL_SUCCESS] tool=${instantResult.tool.name}")
                        } else {
                            Log.w(TAG, "[LOCAL_TOOL_FAILURE] tool=${instantResult.tool.name} " +
                                "result=$resultLabel")
                        }
                        Log.d(TAG, "[LOCAL_LATENCY_TOTAL_MS] tool=${instantResult.tool.name} " +
                            "ms=$totalMs")
                        // Record for the Diagnostics screen — phone-capable
                        // intents always have remoteTouched=false at this
                        // point; the in-memory ring buffer is the audit trail.
                        com.jarvis.assistant.diagnostics.LocalRouteDiagnostics.record(
                            transcript           = transcript,
                            normalisedTranscript = transcriptNormalisedLog,
                            intent               = instantResult.intent,
                            tool                 = instantResult.tool.name,
                            slots                = instantResult.input.params
                                .mapValues { it.value.toString() },
                            result               = resultLabel,
                            latencyMs            = totalMs,
                            remoteTouched        = false,
                            route                = "LOCAL_ONLY",
                        )

                        // ── Record into RecentActionContextStore on success ─
                        // Drives the contextual follow-up router: a bare
                        // "turn off" after flashlight-on now resolves to
                        // the flashlight tool with direction=off.
                        if (resultLabel.startsWith("success")) {
                            recordRecentActionForFollowup(
                                instantResult.intent,
                                instantResult.tool.name,
                                instantResult.input.params,
                            )
                        }

                        // ── Wake-window extension after successful local command ──
                        // Keep the user in the active conversation window so
                        // a follow-up command ("now turn the lights off")
                        // doesn't need a fresh "Jarvis".
                        if (continuation == com.jarvis.assistant.runtime.session
                                .SessionContinuationPolicy.Verdict.CONTINUE_LISTENING
                        ) {
                            attentionGate.extendActiveWindow()
                            Log.d(TAG, "[ATTENTION_ACTIVE_WINDOW_EXTENDED] " +
                                "reason=successful_local_command tool=${instantResult.tool.name}")
                            Log.d(TAG, "[LISTENING_RESTART_SUCCESS] " +
                                "tool=${instantResult.tool.name}")
                        }
                        machine.transition(JarvisState.Listening)
                        syncState(JarvisState.Listening)
                        continue
                    }
                    Log.d(TAG, "[INSTANT_ROUTER_NO_MATCH] " +
                        "reason=${(instantResult as com.jarvis.assistant.voice.routing
                            .InstantCommandRouter.InstantRouteResult.NoMatch).reason} " +
                        "— escalating to LocalFirstRouter / OpenClaw / LLM")

                    // ── Single-pass routing setup (Tier A3) ──────────────────
                    // One ToolRegistry.match per turn, reused by both the
                    // AttentionGate signal builder and the actual route call.
                    val turnIsOnline = contextEngine.isOnline()
                    val turnToolMatch: Pair<com.jarvis.assistant.tools.framework.Tool,
                        com.jarvis.assistant.tools.framework.ToolInput>? =
                        toolRegistry.match(transcript, turnIsOnline)
                    val turnConfidenceTier: TranscriptCorrector.ConfidenceTier =
                        com.jarvis.assistant.audio.stt.TranscriptCorrector
                            .scoreToTier(lastCorrectorScore)

                    // ── AttentionGate ────────────────────────────────────────
                    // Score the transcript against context (mode, active
                    // conversation window, TTS echo, phone call, notification
                    // text, local-command match) BEFORE we record it as a user
                    // turn.  Anything that the gate marks as IGNORE is dropped
                    // silently: no transcript log, no memory write, no TTS.
                    run {
                        val toolMatch = turnToolMatch
                        val signals = AttentionSignals(
                            transcript               = transcript,
                            sttConfidence            = 0f,
                            mode                     = modeController.current,   // B3
                            activeWindowUntilMs      = attentionGate.activeWindowUntilMs,
                            lastJarvisResponseMs     = 0L,
                            nowMs                    = System.currentTimeMillis(),
                            isInCall                 = pendingCallInfo != null,
                            isMediaPlaying           = false,
                            isHeadsetConnected       = bluetoothSco.isHeadsetConnected,
                            screenOn                 = true,
                            isTtsActive              = machine.isIn<JarvisState.Speaking>(),
                            lastTtsText              = ttsEngine.lastSpokenText,
                            localCommandMatch        = toolMatch != null,
                            localCommandToolName     = toolMatch?.first?.name,
                            transcriptCorrectorScore = lastCorrectorScore,
                            looksLikeNotificationText =
                                HomeAssistantNotificationClassifier
                                    .isHomeAssistantAlert("", null, transcript)
                        )
                        when (val decision = attentionGate.gate(signals)) {
                            is AttentionDecision.Ignore -> {
                                Log.d(TAG, "[ATTENTION_DROPPED] target=${decision.target} " +
                                    "reason=${decision.reason} score=${"%.2f".format(decision.score)}")
                                // Stay in Listening; do NOT record this as a user turn.
                                machine.transition(JarvisState.Listening)
                                syncState(JarvisState.Listening)
                                continue
                            }
                            is AttentionDecision.AskIfForMe -> {
                                Log.d(TAG, "[ATTENTION_ASK_BRIEF] \"${decision.prompt}\"")
                                ttsEngine.speak(decision.prompt)
                                machine.transition(JarvisState.Listening)
                                syncState(JarvisState.Listening)
                                continue
                            }
                            is AttentionDecision.Accept -> {
                                // Fall through to existing turn-handling.
                                // Extend the active conversation window so
                                // natural follow-ups skip the gate.
                                attentionGate.extendActiveWindow()
                            }
                        }
                    }

                    speechStateSource.recordUserInteraction()
                    lastSeenTracker.touchUserTurn()  // track presence for gap check-ins
                    DeviceStateStore.update { copy(lastUserUtterance = transcript) }
                    // B5 — move the conversation-turn DAO insert + implicit-
                    // memory detection off the Main coroutine.  The
                    // [implicitMemoryStoredDeferred] is awaited ~900 lines
                    // down when the "Noted." cue is being decided; by then
                    // the IO has long since completed.  Saves 80–200 ms of
                    // perceived pause "after I finish speaking".
                    val implicitMemoryStoredDeferred: kotlinx.coroutines.Deferred<Boolean> =
                        scope.async(Dispatchers.IO) {
                            memoryWriter.writeTurn(sessionId, "user", transcript)
                        }
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
                            InterruptionType.REPLACEMENT -> {
                                // A5 — learn from the user's correction so the same
                                // mishear doesn't happen twice.  We extract:
                                //   heard    = the closest-matching token in the
                                //              previously-heard transcript
                                //   intended = the content of the correction phrase
                                //              ("no I meant X" → "X")
                                //   ctx      = inferred from the original utterance
                                //              (messaging / contact / app / device)
                                com.jarvis.assistant.voice.learning.AliasLearnHelper.tryRecord(
                                    previousTranscript = resumable.userTranscript,
                                    correctionUtter    = transcript,
                                    store              = aliasLearningStore
                                )
                                lastInterrupted = null
                            }
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

                    // ── Response preference detection ────────────────────────
                    // Must run before IntentClassifier — "I prefer…" phrases
                    // would otherwise be captured as memory-store actions.
                    if (::preferenceEngine.isInitialized) {
                        val prefResult = withContext(Dispatchers.IO) {
                            preferenceEngine.tryDetectAndStore(transcript)
                        }
                        if (prefResult != null) {
                            speakAndRecord(prefResult.confirmation)
                            machine.transition(JarvisState.Listening)
                            syncState(JarvisState.Listening)
                            continue
                        }
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
                                is ConversationAction.MuteSuggestion -> memoryHandler.handleMute(action)
                                    .also { promptAssembler.invalidateProfileCache() }
                                is ConversationAction.UnmuteSuggestion -> memoryHandler.handleUnmute(action)
                                    .also { promptAssembler.invalidateProfileCache() }
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

                    // Feed the live thread store so PromptAssembler can tell
                    // the LLM what's still open and what's recently faded.
                    // Skipped for short affirmations/negations which shouldn't
                    // spawn their own thread — the gate below catches those.
                    if (transcript.trim().split(' ').size > 2) {
                        conversationThreads.touchFromUtterance(transcript)
                    }

                    // ── Confirmation gate handshake ───────────────────────────
                    // Any pending risky tool from a prior turn gets first bite
                    // at this utterance. Affirmative → re-dispatch the stashed
                    // tool with skipConfirmation=true. Declined → drop silently.
                    when (val verdict = confirmationGate.consume(transcript)) {
                        is ConfirmationGate.Verdict.Affirmed -> {
                            val stashed = verdict.pending
                            val tool = toolRegistry.findByName(stashed.toolName)
                            if (tool != null) {
                                val r = toolDispatcher.dispatch(
                                    tool, stashed.input, sessionSpeaker,
                                    stashed.originatingTranscript, skipConfirmation = true,
                                )
                                when (r) {
                                    is ToolDispatcher.DispatchResult.Done ->
                                        if (r.spokenFeedback.isNotBlank()) speakAndRecord(r.spokenFeedback)
                                    is ToolDispatcher.DispatchResult.Failed -> speakAndRecord(r.message)
                                    is ToolDispatcher.DispatchResult.Denied -> speakAndRecord(r.message)
                                    is ToolDispatcher.DispatchResult.SilentExit,
                                    is ToolDispatcher.DispatchResult.AugmentedLlm,
                                    is ToolDispatcher.DispatchResult.LlmFollowUp,
                                    is ToolDispatcher.DispatchResult.NeedsConfirmation -> Unit
                                }
                            } else {
                                speakAndRecord("That tool's gone — skipping it.")
                            }
                            machine.transition(JarvisState.Listening)
                            syncState(JarvisState.Listening)
                            continue
                        }
                        is ConfirmationGate.Verdict.Declined -> {
                            speakAndRecord("Dropped it.")
                            machine.transition(JarvisState.Listening)
                            syncState(JarvisState.Listening)
                            continue
                        }
                        ConfirmationGate.Verdict.None -> Unit
                    }

                    // ── Conversation intent classification ─────────────────────
                    // Classify BEFORE tool matching so casual/personal messages
                    // never reach the tool layer (and never trigger web search).
                    val convIntent = ConversationClassifier.classify(transcript)
                    LatencyTracker.mark("INTENT_CLASSIFIED_CONV")

                    // ── Tool dispatch ──────────────────────────────────────────
                    // Reuse the match that the AttentionGate signal builder
                    // already computed for this turn (A3).  Avoids a second
                    // ToolRegistry.match regex sweep over the same transcript.
                    val isOnline = turnIsOnline
                    // Gate: PERSONAL_UPDATE and CASUAL_CHAT skip tools entirely.
                    val matched = if (ToolUsePolicy.allowsTools(convIntent)) {
                        turnToolMatch
                    } else {
                        null
                    }
                    LatencyTracker.mark("TOOL_MATCHED")

                    // Route through LocalFirstRouter so the verdict + reason
                    // are loggable and we can branch on ConfidenceTier (A4).
                    // The router never re-runs match() — we hand it the
                    // precomputed result.
                    val routeOutcome = localFirstRouter.route(
                        transcript        = transcript,
                        isOnline          = isOnline,
                        precomputedMatch  = matched,
                        tier              = turnConfidenceTier
                    )
                    when (routeOutcome) {
                        is com.jarvis.assistant.voice.routing.LocalFirstRouter.RouteOutcome.Clarify -> {
                            // Medium confidence + risky tool, OR low confidence.
                            // Echo back what we'd do and ask for explicit go/no-go.
                            Log.d(TAG, "[CONFIDENCE_CONFIRM_MEDIUM_RISKY] " +
                                "tool=${routeOutcome.tool.name} tier=${routeOutcome.tier}")
                            ttsEngine.speak(routeOutcome.confirmPrompt)
                            machine.transition(JarvisState.Listening)
                            syncState(JarvisState.Listening)
                            continue
                        }
                        is com.jarvis.assistant.voice.routing.LocalFirstRouter.RouteOutcome.Ignore -> {
                            Log.d(TAG, "[CONFIDENCE_CLARIFY_LOW] route=Ignore " +
                                "reason=${routeOutcome.reason}")
                            machine.transition(JarvisState.Listening)
                            syncState(JarvisState.Listening)
                            continue
                        }
                        is com.jarvis.assistant.voice.routing.LocalFirstRouter.RouteOutcome.Local -> {
                            Log.d(TAG, "[CONFIDENCE_EXECUTE_HIGH] tool=${routeOutcome.tool.name} " +
                                "tier=${routeOutcome.tier}")
                            // fall through to dispatch path below
                        }
                        is com.jarvis.assistant.voice.routing.LocalFirstRouter.RouteOutcome.OpenClawFallback -> {
                            // fall through — existing OpenClaw block handles this
                        }
                    }

                    if (matched != null) {
                        val (tool, input) = matched
                        // ROUTE_LOCAL_MATCH / ROUTE_LOCAL_EXECUTE — explicit
                        // grep targets for the local-first routing path.
                        Log.d(TAG, "[ROUTE_LOCAL_MATCH] tool=${tool.name} transcript=\"$transcript\"")
                        localFirstRouter.logLocalExecute(tool.name)
                        syncState(machine.current)

                        val dispatchResult = toolDispatcher.dispatch(
                            tool, input, sessionSpeaker, transcript,
                            confidenceTier = turnConfidenceTier      // gate confirmations by tier
                        )

                        // Domain tracking — update lastActiveDomain so preference extraction
                        // has a context fallback for the next "I prefer…" utterance.
                        if (::preferenceEngine.isInitialized) {
                            com.jarvis.assistant.preferences.ResponseDomain.TOOL_DOMAIN_MAP[tool.name]
                                ?.let { preferenceEngine.lastActiveDomain = it }
                        }

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
                                if (r.spokenFeedback.isNotBlank()) {
                                    rememberFactReplyIfApplicable(r.hints?.toolName, r.spokenFeedback)
                                    speakAndRecord(r.spokenFeedback)
                                }
                                machine.transition(JarvisState.Listening)
                                syncState(JarvisState.Listening)
                                continue
                            }
                            is ToolDispatcher.DispatchResult.Failed -> {
                                // ── Messaging body/recipient clarify intercept ────
                                // When a messaging tool returns Failure because a
                                // slot is missing ("What should the WhatsApp to
                                // Mike say?"), the tool already knew the channel
                                // + (often) the recipient.  Without parking a
                                // PendingMessageIntent here, the user's NEXT
                                // utterance ("Hello") gets routed as fresh
                                // small-talk and the LLM happily replies "Hey"
                                // — the WhatsApp never sends.
                                //
                                // Fix: detect the messaging-clarify shape from
                                // the failed hints, park a PendingMessageIntent
                                // pre-filled with whatever the tool already
                                // parsed, then speak the same prompt the tool
                                // returned.  The next turn's pending-intent
                                // intercept (above startCallEventCollection)
                                // picks it up and merges the body in.
                                val toolName = r.hints?.toolName
                                if (toolName == "whatsapp_message" || toolName == "send_sms") {
                                    val channel = if (toolName == "whatsapp_message")
                                        com.jarvis.assistant.tools.device.MessageIntentParser.Channel.WHATSAPP
                                    else
                                        com.jarvis.assistant.tools.device.MessageIntentParser.Channel.SMS
                                    val knownRecipient = r.hints?.input?.param("name").orEmpty()
                                    val knownBody      = r.hints?.input?.param("message").orEmpty()
                                    pendingMessageIntent = com.jarvis.assistant.tools.device.messaging
                                        .PendingMessageIntent.create(
                                            channel   = channel,
                                            recipient = knownRecipient,
                                            body      = knownBody,
                                        )
                                    Log.d(TAG, "[MSG_PENDING_PARKED] reason=tool_failure " +
                                        "channel=$channel recipient=\"$knownRecipient\" " +
                                        "body_blank=${knownBody.isBlank()} ttl_ms=20000")
                                }
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
                            is ToolDispatcher.DispatchResult.NeedsConfirmation -> {
                                Log.d(TAG, "[CONFIRMATION_LISTEN_STARTED] tool=${r.toolName} " +
                                    "pending=${r.pendingId}")
                                speakAndRecord(r.prompt)
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

                    // ── Messaging hard guard ──────────────────────────────────────
                    // Messaging utterance with missing slots — never OpenClaw.
                    // Park a PendingMessageIntent so the user's NEXT utterance
                    // ("Mike saying hello") is intercepted at the top of the
                    // next turn and merged in directly, without going through
                    // AttentionGate or the OpenClaw fallback.
                    if (matched == null &&
                        com.jarvis.assistant.tools.device.MessageIntentParser
                            .looksLikeMessagingCommand(transcript)
                    ) {
                        Log.d(TAG, "[MSG_INCOMPLETE_LOCAL_CLARIFY] transcript=\"$transcript\"")
                        val isWa = Regex("""\bwhatsapp|whats\s+app|\bwa\b""",
                            RegexOption.IGNORE_CASE).containsMatchIn(transcript)
                        val channel = if (isWa)
                            com.jarvis.assistant.tools.device.MessageIntentParser.Channel.WHATSAPP
                        else
                            com.jarvis.assistant.tools.device.MessageIntentParser.Channel.SMS
                        pendingMessageIntent = com.jarvis.assistant.tools.device.messaging
                            .PendingMessageIntent.create(channel = channel)
                        Log.d(TAG, "[MSG_PENDING_PARKED] channel=$channel ttl_ms=20000")
                        val askPrompt = if (isWa)
                            "Who should I send the WhatsApp to and what should it say?"
                        else
                            "Who should I send the message to and what should it say?"
                        speakAndRecord(askPrompt)
                        machine.transition(JarvisState.Listening)
                        syncState(JarvisState.Listening)
                        continue
                    }

                    // ── OpenClaw remote routing (before local LLM fallback) ────────
                    // At this point no local tool matched.  Emit the explicit
                    // ROUTE_OPENCLAW_FALLBACK log so the routing trail is
                    // grep-able end-to-end.
                    if (matched == null) {
                        Log.d(TAG, "[ROUTE_NO_LOCAL_MATCH] transcript=\"$transcript\"")
                        localFirstRouter.logOpenClawFallback("no_local_tool_matched")
                    }
                    // ── Phone-capable tripwire ────────────────────────────────
                    // Sprint mission: if Android can do it locally, it MUST.
                    // The InstantCommandRouter runs first; if a phone-capable
                    // transcript STILL reaches the remote routing path that
                    // is a regression — log loudly, file a bug, and refuse
                    // to escalate.
                    if (com.jarvis.assistant.voice.routing.PhoneCapableIntents
                            .isInvalidRemoteRoute(transcript, remoteSubsystem = "openclaw")
                    ) {
                        speakAndRecord("That didn't work — I've logged it.")
                        machine.transition(JarvisState.Listening)
                        syncState(JarvisState.Listening)
                        continue
                    }
                    if (!openClawRouter.shouldRoute()) {
                        Log.d(TAG, "[OPENCLAW_DISABLED] " +
                            "shouldRoute=false — staying local-only for this turn")
                    }
                    if (openClawRouter.shouldRoute()) {
                        val route = openClawRouter.classify(transcript)

                        // Keyword-triggered failures are always surfaced to the user.
                        // Auto-routed REMOTE_LONG failures fall through to local LLM silently.
                        val ocKeyword = openClawRepo.snapshot().keyword.trim()
                        val keywordTriggered = ocKeyword.isNotBlank() &&
                            transcript.trim().lowercase().startsWith(ocKeyword.lowercase())

                        if (route == com.jarvis.assistant.remote.openclaw.RouteType.REMOTE_LONG) {
                            ttsEngine.speak("Looking into that.")
                        }

                        val streamFlow = openClawRouter.executeStreaming(
                            transcript, sessionId,
                            llmRouter.conversationStore.getRecentMessages(6)
                        )

                        if (streamFlow != null) {
                            val fullResponse           = StringBuilder()
                            var speakingStarted        = false
                            // Tracks whether the 3 s first-token watchdog fired.
                            // Lets the CancellationException handler distinguish
                            // "no tokens in 3 s → fall through to local LLM" from
                            // "barge-in cancellation → rethrow".
                            val firstTokenTimeoutFired = java.util.concurrent.atomic.AtomicBoolean(false)
                            var firstTokenReceived     = false
                            // Arm the barge-in tracker for this stream.  If
                            // handleBargeIn fires while the stream is in flight,
                            // [bargeInFired] flips to true and the catch block
                            // below logs BARGE_IN_STREAM_CANCELLED instead of
                            // mis-attributing the cancel to a timeout.
                            bargeInFired.set(false)
                            Log.d(TAG, "[BARGE_IN_ARMED] for_openclaw_stream")
                            LatencyTracker.mark("OPENCLAW_REQUEST_START")
                            try {
                                withTimeout(openClawRepo.snapshot().timeoutMs) {
                                    // ── First-token watchdog ──────────────────────────────
                                    // If the remote endpoint doesn't yield a sentence within
                                    // 3 s we cancel this scope and fall through to the local
                                    // LLM rather than keeping the user waiting silently.
                                    val timeoutScopeJob = coroutineContext[kotlinx.coroutines.Job]!!
                                    val watchdogJob = launch {
                                        // Tightened from 3 s → 1.5 s per the
                                        // local-first refactor: OpenClaw is
                                        // the deeper brain, not the first
                                        // stop.  If the remote endpoint
                                        // hasn't produced a sentence in
                                        // 1.5 s, fail fast to the local LLM
                                        // rather than keep the user waiting.
                                        delay(1_500L)
                                        if (!firstTokenReceived) {
                                            firstTokenTimeoutFired.set(true)
                                            Log.w(TAG, "[OPENCLAW_FIRST_TOKEN_TIMEOUT] no sentence in 1.5 s " +
                                                "— cancelling stream, falling through to local LLM")
                                            LatencyTracker.mark("OPENCLAW_FIRST_TOKEN_TIMEOUT")
                                            timeoutScopeJob.cancel()
                                        }
                                    }
                                    streamFlow.collect { sentence ->
                                        if (!firstTokenReceived) {
                                            firstTokenReceived = true
                                            watchdogJob.cancel()
                                            LatencyTracker.mark("OPENCLAW_FIRST_TOKEN")
                                        }
                                        fullResponse.append(sentence).append(" ")
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
                                        if (settings.voiceResponse && machine.isIn<JarvisState.Speaking>()) {
                                            ttsEngine.speak(sentence)
                                        }
                                    }
                                }
                                if (speakingStarted) {
                                    if (settings.voiceResponse) bargeIn.stop()
                                    DeviceStateStore.update { copy(ttsPlaying = false) }
                                }
                                LatencyTracker.mark("OPENCLAW_COMPLETE")

                                if (fullResponse.isNotBlank()) {
                                    val response = fullResponse.toString().trim()
                                    llmRouter.conversationStore.addMessage("user", transcript)
                                    llmRouter.conversationStore.addMessage("assistant", response)
                                    memoryWriter.writeTurn(sessionId, "assistant", response)
                                    DeviceStateStore.update { copy(lastAssistantResponse = response) }
                                    machine.transition(JarvisState.Listening)
                                    syncState(JarvisState.Listening)
                                    continue
                                }
                                // Empty stream — fall through to local LLM

                            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                                Log.d(TAG, "[OPENCLAW_CANCEL_BEGIN] cause=timeout " +
                                    "timeoutMs=${openClawRepo.snapshot().timeoutMs}")
                                if (speakingStarted) {
                                    if (settings.voiceResponse) bargeIn.stop()
                                    DeviceStateStore.update { copy(ttsPlaying = false) }
                                }
                                Log.d(TAG, "[OPENCLAW_CANCEL_DONE] cause=timeout — fall through path next")
                                if (keywordTriggered) {
                                    speakAndRecord(com.jarvis.assistant.remote.openclaw.OpenClawError.TimedOut.spokenMessage)
                                    machine.transition(JarvisState.Listening)
                                    syncState(JarvisState.Listening)
                                    continue
                                }
                                // Auto-routed timeout — fall through to local LLM

                            } catch (e: CancellationException) {
                                if (speakingStarted) {
                                    if (settings.voiceResponse) bargeIn.stop()
                                    DeviceStateStore.update { copy(ttsPlaying = false) }
                                }
                                // Disambiguate the cancellation cause:
                                //   bargeInFired  → user spoke over Jarvis
                                //   firstTokenTimeoutFired → 3 s watchdog
                                //   else          → upstream cancel (caller)
                                if (bargeInFired.get()) {
                                    Log.d(TAG, "[BARGE_IN_STREAM_CANCELLED] openclaw")
                                    Log.d(TAG, "[BARGE_IN_CANCEL_CAUSE_CONFIRMED] cause=barge_in")
                                    // Barge-in already handled state transitions
                                    // (Interrupted → Listening).  Skip the rest.
                                    continue
                                }
                                if (firstTokenTimeoutFired.get()) {
                                    // First-token watchdog fired — treat like an auto-routed
                                    // timeout: surface error to user only if keyword-triggered,
                                    // otherwise fall through silently to local LLM.
                                    if (keywordTriggered) {
                                        speakAndRecord(com.jarvis.assistant.remote.openclaw.OpenClawError.TimedOut.spokenMessage)
                                        machine.transition(JarvisState.Listening)
                                        syncState(JarvisState.Listening)
                                        continue
                                    }
                                    // Auto-routed first-token timeout — fall through to local LLM
                                } else {
                                    // Genuine cancellation (e.g. barge-in) — propagate upward.
                                    throw e
                                }

                            } catch (e: Exception) {
                                if (speakingStarted) {
                                    if (settings.voiceResponse) bargeIn.stop()
                                    DeviceStateStore.update { copy(ttsPlaying = false) }
                                }
                                Log.w(TAG, "OpenClaw stream error: ${e.message}")
                                val isAuth = e.message?.contains("401") == true ||
                                             e.message?.contains("403") == true
                                if (isAuth || keywordTriggered) {
                                    val error = if (isAuth)
                                        com.jarvis.assistant.remote.openclaw.OpenClawError.AuthFailed
                                    else
                                        com.jarvis.assistant.remote.openclaw.OpenClawError.Unreachable(e.message ?: "")
                                    speakAndRecord(error.spokenMessage)
                                    machine.transition(JarvisState.Listening)
                                    syncState(JarvisState.Listening)
                                    continue
                                }
                                // Auto-routed failure — fall through to local LLM
                            }
                        }
                    }

                    // ── LLM inference via PromptAssembler + streaming ─────────────
                    Log.d(TAG, "[FALLBACK_LOCAL_LLM_BEGIN] " +
                        "reason=openclaw_unavailable_or_no_local_match")
                    Log.d(TAG, "[MEMORY_RETRIEVE_BEGIN] transcript=\"${transcript.take(60)}\"")
                    LatencyTracker.mark("LLM_REQUEST_START")
                    Log.d(TAG, "[LLM_REQUEST_START] online=$isOnline")
                    streamAndSpeak(transcript, isOnline)
                    Log.d(TAG, "[MEMORY_RETRIEVE_DONE] (folded into streamAndSpeak)")

                    // If an implicit memory was stored from this turn, give a brief
                    // verbal cue so the user knows something was retained.
                    // B5: the deferred is almost always already complete by now
                    // (the LLM stream above takes hundreds of ms) so the await
                    // here is effectively free.
                    val implicitMemoryStored = implicitMemoryStoredDeferred.await()
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
        val tBarge = android.os.SystemClock.elapsedRealtime()
        Log.d(TAG, "[BARGE_IN_TRIGGERED] t=$tBarge")
        bargeInFired.set(true)
        ttsEngine.stopSpeaking()
        Log.d(TAG, "[BARGE_IN_TTS_STOPPED] +${android.os.SystemClock.elapsedRealtime() - tBarge}ms")
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
        Log.d(TAG, "[BARGE_IN_LISTEN_STARTED]")
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
            val prefFrag = if (::preferenceEngine.isInitialized)
                kotlinx.coroutines.withContext(Dispatchers.IO) { preferenceEngine.buildPromptFragment() }
            else null
            val messages = promptAssembler.assemble(
                transcript, history, maxMemories = 2,
                speakerContext      = sessionSpeaker,
                presence            = currentPresence(),
                preferencesFragment = prefFrag,
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
            val prefFrag2 = if (::preferenceEngine.isInitialized)
                kotlinx.coroutines.withContext(Dispatchers.IO) { preferenceEngine.buildPromptFragment() }
            else null
            val messages = promptAssembler.assemble(
                transcript, history, maxMemories = 4,
                speakerContext      = sessionSpeaker,
                presence            = currentPresence(),
                preferencesFragment = prefFrag2,
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
                            // Function-calling text replies skip the streaming
                            // ReasoningTagStripper, so route them through the
                            // same post-hoc strip that complete()/completeSilent()
                            // use — otherwise <thinking> blocks would slip into
                            // TTS on the FC path.
                            val cleaned = llmRouter.stripReasoningTags(fcResult.content)
                            if (cleaned.isNotBlank()) {
                                llmRouter.conversationStore.addMessage("assistant", cleaned)
                                speakAndRecord(cleaned)
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
                            val feedbacks = coroutineScope {
                                callList.map { tc ->
                                    async<String?>(Dispatchers.IO) {
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
                            }

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
                    LatencyTracker.mark("LLM_FIRST_TOKEN")
                    LatencyTracker.mark("LLM_FIRST_SENTENCE")
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
        } catch (e: com.jarvis.assistant.llm.LlmRateLimitedException) {
            // ── HTTP 429 / rate-limit fallback ──────────────────────────────
            // Don't stall, don't crash, don't retry against the same provider
            // — say a short message and let the next turn try again.  If the
            // utterance is a simple intent it should have matched a local
            // tool higher up; getting here means the LLM was genuinely the
            // right route, so we just tell the user to wait.
            Log.w(TAG, "[LLM_RATE_LIMITED] ${e.message}")
            bargeIn.stop()
            DeviceStateStore.update { copy(ttsPlaying = false) }
            speakAndRecord("I'm hitting a rate limit on the cloud model right now. Give me a minute.")
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

        val prefFrag3 = if (::preferenceEngine.isInitialized)
            kotlinx.coroutines.withContext(Dispatchers.IO) { preferenceEngine.buildPromptFragment() }
        else null
        val messages = promptAssembler.assemble(
            userQuery           = resumable.userTranscript,
            conversationHistory = history,
            maxMemories         = 2,
            speakerContext      = sessionSpeaker,
            presence            = currentPresence(),
            preferencesFragment = prefFrag3,
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

    /**
     * Returns a morning briefing string if this is the first wake of the day before 10 am,
     * null otherwise (so the caller falls back to a random ack).
     */
    /**
     * Map a successful local-tool dispatch into a [RecentActionContextStore]
     * entry.  Different tools want different action types — flashlight
     * and smart_home are DEVICE_TOGGLE so "turn off" resolves, camera
     * is MEDIA_CAPTURE so "show me the selfie" resolves, etc.
     *
     * Pure / cheap.  Called fire-and-forget after every Success — never
     * throws.
     */
    private fun recordRecentActionForFollowup(
        intent: String,
        toolName: String,
        params: Map<String, String>,
    ) = runCatching {
        val type = when (intent) {
            "FLASHLIGHT"             -> com.jarvis.assistant.runtime.context
                .RecentActionContextStore.ActionType.DEVICE_TOGGLE
            "HOME_ASSISTANT_DEVICE"  -> com.jarvis.assistant.runtime.context
                .RecentActionContextStore.ActionType.SMART_HOME
            "VOLUME"                 -> com.jarvis.assistant.runtime.context
                .RecentActionContextStore.ActionType.VOLUME
            "MEDIA"                  -> com.jarvis.assistant.runtime.context
                .RecentActionContextStore.ActionType.MEDIA_PLAYBACK
            "CAMERA"                 -> com.jarvis.assistant.runtime.context
                .RecentActionContextStore.ActionType.MEDIA_CAPTURE
            "OPEN_APP"               -> com.jarvis.assistant.runtime.context
                .RecentActionContextStore.ActionType.OPEN_APP
            "CALL", "END_CALL"       -> com.jarvis.assistant.runtime.context
                .RecentActionContextStore.ActionType.CALL
            "SEND_MESSAGE"           -> com.jarvis.assistant.runtime.context
                .RecentActionContextStore.ActionType.MESSAGING
            "LOCATION"               -> com.jarvis.assistant.runtime.context
                .RecentActionContextStore.ActionType.NAVIGATION
            "CALENDAR"               -> com.jarvis.assistant.runtime.context
                .RecentActionContextStore.ActionType.CALENDAR
            else                     -> com.jarvis.assistant.runtime.context
                .RecentActionContextStore.ActionType.OTHER
        }
        // Friendly "target" — what the action touched.  For flashlight
        // / volume / media this is the tool's own short name; for
        // smart_home it's the entity slot.
        val target = params["target"] ?: params["entity"] ?: params["app"]
            ?: when (toolName) {
                "flashlight"      -> "flashlight"
                "volume_control"  -> "volume"
                "media_control"   -> "media"
                else              -> null
            }
        // ── Media URI propagation ─────────────────────────────────────
        // Camera captures publish their file path to MediaContextStore.
        // Pull it back here so the contextual follow-up resolver can
        // hand a real URI to ViewMediaTool / ShareMediaTool when the
        // user says "show me the selfie" / "share that".  Without
        // this, resolveShowMedia always returned NotApplicable and
        // the camera tool fired a second time.
        val mediaUri = if (type == com.jarvis.assistant.runtime.context
                .RecentActionContextStore.ActionType.MEDIA_CAPTURE) {
            com.jarvis.assistant.tools.device.media.MediaContextStore
                .peek()?.filePath
        } else null
        val enrichedParams = if (mediaUri != null && type == com.jarvis.assistant
                .runtime.context.RecentActionContextStore.ActionType.MEDIA_CAPTURE) {
            params + mapOf("kind" to (params["facing"]?.let {
                if (it == "front") "selfie" else "photo"
            } ?: "photo"))
        } else params

        recentActionContext.record(
            type     = type,
            tool     = toolName,
            target   = target,
            params   = enrichedParams,
            mediaUri = mediaUri,
        )
    }.getOrNull()

    /**
     * Tool names whose spoken result is a *fact-style* answer — the kind of
     * reply the user typically follows up on with a short clarifier ("what
     * number?", "and the postcode?", "how far?").  Each entry maps to a
     * short topic tag for the prompt fragment.  Anything not in this map
     * is intentionally ignored: tool acknowledgements like "Timer set." or
     * "Opening Spotify." do not seed follow-up context.
     */
    private val FACT_REPLY_TOOLS: Map<String, String> = mapOf(
        "where_am_i" to "location",
        "weather"    to "weather",
        "battery"    to "battery",
        "time"       to "time",
        "date"       to "date",
        "calendar_lookup" to "calendar",
    )

    private fun rememberFactReplyIfApplicable(toolName: String?, spoken: String) {
        val topic = toolName?.let { FACT_REPLY_TOOLS[it] } ?: return
        recentFactCarrier.remember(topic, spoken)
        Log.d(TAG, "[RECENT_FACT_REMEMBERED] tool=$toolName topic=$topic")
    }

    private suspend fun tryMorningBriefing(): String? {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (hour >= 10) return null
        val tool = toolRegistry.findByName("daily_briefing")
            as? com.jarvis.assistant.tools.device.DailyBriefingTool ?: return null
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        if (tool.getLastBriefingDate() == today) return null
        return tool.buildBriefText()
    }

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
            Log.d(TAG, "[AUDIO_FOCUS_REQUEST] speakAndRecord")
            audioFocus.requestFocus()
            try {
                bargeIn.start()
                ttsEngine.speak(formatted)
                bargeIn.stop()
            } finally {
                audioFocus.abandonFocus()
                Log.d(TAG, "[AUDIO_FOCUS_ABANDON] speakAndRecord done")
            }
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

    /**
     * Tier-B ambient snapshot.  Cheap, no I/O; reads from already-cached
     * sources.  Called every 5 s by [AmbientContextAggregator] and once per
     * turn from the attention-signals builder.
     */
    private fun snapshotAmbientContext(): com.jarvis.assistant.context.AmbientContextSnapshot {
        val now         = System.currentTimeMillis()
        val speech      = speechStateSource.getSpeechState()
        val lastUserMs  = lastSeenTracker.lastUserTurnMs.takeIf { it > 0L } ?: 0L
        val msSinceUser = if (lastUserMs > 0L) now - lastUserMs else Long.MAX_VALUE
        return com.jarvis.assistant.context.AmbientContextSnapshot(
            timestampMs            = now,
            isDriving              = drivingModeManager.isDriving,
            isInCall               = pendingCallInfo != null,
            isJarvisSpeaking       = speech.isSpeaking,
            isJarvisListening      = speech.isListening,
            batteryPercent         = null,    // wire BatteryEventAdapter signal later
            isCharging             = false,
            screenOn               = true,    // wire ScreenStateReceiver later
            isHeadsetConnected     = bluetoothSco.isHeadsetConnected,
            isMediaPlaying         = false,   // wire AudioManager.isMusicActive later
            foregroundAppPackage   = null,
            isOnline               = contextEngine.isOnline(),
            presence               = currentPresence(),
            msSinceLastInteraction = msSinceUser
        )
    }

    private fun backToWakeWord() {
        // B4 — drop the AttentionGate active conversation window whenever
        // we return to wake-word mode.  Without this, a 15 s "natural follow-
        // up" window outlives the conversation and can swallow human speech
        // in the same room when the user has already moved on.
        attentionGate.closeActiveWindow()
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
        // NonCancellable so a stop() called from a cancelled scope still
        // completes the DB writes, and a 2 s ceiling so a stuck IO threadpool
        // can never turn this into an ANR.  The previous unbounded
        // runBlocking deadlocked the caller if every IO thread was busy.
        runBlocking(NonCancellable + Dispatchers.IO) {
            try {
                withTimeout(2_000L) {
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
            } catch (e: Exception) {
                // Timeout — surface but don't crash the shutdown path.
                Log.w(TAG, "flushSession: timed out after 2s, partial flush — ${e.message}")
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
