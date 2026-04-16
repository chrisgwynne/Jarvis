package com.jarvis.assistant.followup

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.telephony.SmsManager
import android.util.Log
import com.jarvis.assistant.reminders.ReminderParser
import com.jarvis.assistant.reminders.ReminderRepository
import com.jarvis.assistant.reminders.db.entity.ScheduledItemType
import com.jarvis.assistant.tools.ContactLookup
import com.jarvis.assistant.tools.device.AppAliasStore
import com.jarvis.assistant.tools.device.AppResolver
import com.jarvis.assistant.util.SettingsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FollowUpCoordinator — the single entry point for the context-aware
 * follow-up system.
 *
 * POSITION IN THE PIPELINE:
 *   JarvisRuntime calls [process] BEFORE IntentClassifier / ToolRegistry / LLM.
 *   If [process] returns [FlowResult.PassThrough] the pipeline continues normally.
 *   Any other result means the follow-up system has handled the utterance.
 *
 * WHAT IT DOES:
 *   1. If an active flow exists: classify the utterance against it and either
 *      advance the flow, ask the next question, execute, or cancel.
 *   2. If no active flow: try to detect a NEW flow that needs multiple turns
 *      (e.g. "message Chris" without a body, "remind me tomorrow" without a subject).
 *   3. After every utterance: update [EntityTracker] with any new entities.
 *
 * WHAT IT DOES NOT DO:
 *   • It does NOT intercept fully-specified commands ("remind me in 20 minutes
 *     to take medication") — those fall through to IntentClassifier/ToolRegistry.
 *   • It does NOT persist flows to disk — flows are short-lived operational state.
 *   • It does NOT replace long-term memory.
 */
class FollowUpCoordinator(
    private val context: Context,
    private val contactLookup: ContactLookup,
    private val reminderRepository: ReminderRepository,
    private val settings: SettingsStore? = null,
    val entityTracker: EntityTracker = EntityTracker()
) {

    companion object {
        private const val TAG = "FollowUpCoordinator"
    }

    private val flowContext = FollowUpContext()
    private val persistence = FlowStatePersistence(context)
    private val appResolver = AppResolver(context, AppAliasStore(context))

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Process one user utterance against the current follow-up context.
     *
     * @return [FlowResult.PassThrough] if nothing was handled here;
     *         [FlowResult.AwaitingInput], [FlowResult.Complete], or
     *         [FlowResult.Cancelled] if the follow-up system handled it.
     */
    suspend fun process(utterance: String): FlowResult {
        // Always update entity tracker so pronouns work on the next turn
        entityTracker.updateFromUtterance(utterance)

        // ── 1. Continue an existing active flow ────────────────────────────
        val active = flowContext.activeFlow
        if (active != null) {
            val result = continueFlow(utterance, active)
            if (result !is FlowResult.PassThrough) {
                entityTracker.decaySalience()
                // Persist updated flow state (null if completed/cancelled)
                val currentFlow = flowContext.activeFlow
                persistence.saveFlow(currentFlow)
                return result
            }
            // UNRELATED — fall through and treat as a new request
        }

        // ── 2. Try to start a new multi-turn flow ──────────────────────────
        val started = tryStartFlow(utterance)
        if (started != null) {
            entityTracker.decaySalience()
            // Persist newly started flow
            persistence.saveFlow(flowContext.activeFlow)
            return started
        }

        // ── 3. Nothing to do here ──────────────────────────────────────────
        return FlowResult.PassThrough
    }

    /**
     * Restore a previously active flow from SharedPreferences.
     * Call this when JarvisService (re)starts to resume an in-progress flow.
     */
    fun restorePersistedFlow() {
        val restored = persistence.restoreFlow() ?: return
        Log.d(TAG, "Restored persisted ${restored.type} flow (expires ${restored.expiresAt})")
        flowContext.setActiveFlow(restored)
    }

    /** Force-clear the active flow without speaking — used on service stop. */
    fun clearActiveFlow() {
        flowContext.clearActiveFlow()
        persistence.saveFlow(null)
    }

    // ── Flow continuation ──────────────────────────────────────────────────────

    private suspend fun continueFlow(utterance: String, flow: ActiveFlow): FlowResult {
        Log.d(TAG, "Continuing ${flow.type} flow (turn ${flow.turnCount}, missing=${flow.missingSlots})")

        val cls = FollowUpResolver.classify(utterance, flow, entityTracker)

        return when (cls) {

            is FollowUpResolver.Classification.Cancellation -> {
                Log.d(TAG, "Flow cancelled by user")
                flow.markCancelled()
                flowContext.setActiveFlow(null)
                FlowResult.Cancelled()
            }

            is FollowUpResolver.Classification.Confirmation -> {
                // All slots were already filled — execute now
                if (flow.allSlotsCollected()) {
                    executeFlow(flow)
                } else {
                    FlowResult.PassThrough
                }
            }

            is FollowUpResolver.Classification.Denial -> {
                // User said "no" — ask the current question again or pass through
                val question = ClarificationManager.nextQuestion(flow)
                if (question != null) FlowResult.AwaitingInput(question)
                else FlowResult.PassThrough
            }

            is FollowUpResolver.Classification.Correction -> {
                Log.d(TAG, "Correcting slot ${cls.slot} → '${cls.newValue}'")
                flow.replaceSlot(cls.slot, cls.newValue)

                if (flow.allSlotsCollected()) {
                    executeFlow(flow)
                } else {
                    val question = ClarificationManager.nextQuestion(flow) ?: return FlowResult.PassThrough
                    flow.lastPrompt = question
                    FlowResult.AwaitingInput("Updated. $question")
                }
            }

            is FollowUpResolver.Classification.SlotFill -> {
                Log.d(TAG, "Filled slot ${cls.slot} = '${cls.value}' (conf=${cls.confidence})")
                flow.fillSlot(cls.slot, cls.value, cls.confidence)

                // Track any contact entity we just confirmed
                if (cls.slot == SlotKey.TARGET_CONTACT) {
                    entityTracker.track(EntityReference(EntityType.CONTACT, label = cls.value))
                }

                if (flow.allSlotsCollected()) {
                    executeFlow(flow)
                } else {
                    val question = ClarificationManager.nextQuestion(flow) ?: return FlowResult.PassThrough
                    flow.lastPrompt = question
                    flow.expectedSlot = flow.missingSlots.firstOrNull()
                    FlowResult.AwaitingInput(question)
                }
            }

            is FollowUpResolver.Classification.Unrelated -> {
                Log.d(TAG, "Utterance is unrelated to active flow — passing through")
                FlowResult.PassThrough
            }
        }
    }

    // ── New flow detection ─────────────────────────────────────────────────────

    private suspend fun tryStartFlow(utterance: String): FlowResult? {
        detectMessageFlow(utterance)?.let { flow ->
            if (flow.allSlotsCollected()) return null  // let ToolRegistry handle complete commands
            flowContext.setActiveFlow(flow)
            val question = ClarificationManager.nextQuestion(flow) ?: return null
            flow.lastPrompt = question
            flow.expectedSlot = flow.missingSlots.firstOrNull()
            Log.d(TAG, "Started ${flow.type} flow, asking: $question")
            return FlowResult.AwaitingInput(question)
        }

        detectReminderFlow(utterance)?.let { flow ->
            if (flow.allSlotsCollected()) return null  // IntentClassifier handles this
            flowContext.setActiveFlow(flow)
            val question = ClarificationManager.nextQuestion(flow) ?: return null
            flow.lastPrompt = question
            flow.expectedSlot = flow.missingSlots.firstOrNull()
            Log.d(TAG, "Started ${flow.type} flow, asking: $question")
            return FlowResult.AwaitingInput(question)
        }

        detectCallFlow(utterance)?.let { flow ->
            if (flow.allSlotsCollected()) return null  // ToolRegistry handles fully-specified calls
            flowContext.setActiveFlow(flow)
            val question = ClarificationManager.nextQuestion(flow) ?: return null
            flow.lastPrompt = question
            flow.expectedSlot = flow.missingSlots.firstOrNull()
            Log.d(TAG, "Started ${flow.type} flow, asking: $question")
            return FlowResult.AwaitingInput(question)
        }

        detectEmailFlow(utterance)?.let { flow ->
            if (flow.allSlotsCollected()) return@let executeFlow(flow)  // all inline → execute now
            flowContext.setActiveFlow(flow)
            val question = ClarificationManager.nextQuestion(flow) ?: return null
            flow.lastPrompt = question
            flow.expectedSlot = flow.missingSlots.firstOrNull()
            Log.d(TAG, "Started ${flow.type} flow, asking: $question")
            FlowResult.AwaitingInput(question)
        }?.let { return it }

        detectTimerFlow(utterance)?.let { flow ->
            if (flow.allSlotsCollected()) return@let executeFlow(flow)
            flowContext.setActiveFlow(flow)
            val question = ClarificationManager.nextQuestion(flow) ?: return null
            flow.lastPrompt = question
            flow.expectedSlot = flow.missingSlots.firstOrNull()
            Log.d(TAG, "Started ${flow.type} flow, asking: $question")
            FlowResult.AwaitingInput(question)
        }?.let { return it }

        detectAppLaunchFlow(utterance)?.let { flow ->
            if (flow.allSlotsCollected()) return@let executeFlow(flow)
            flowContext.setActiveFlow(flow)
            val question = ClarificationManager.nextQuestion(flow) ?: return null
            flow.lastPrompt = question
            flow.expectedSlot = flow.missingSlots.firstOrNull()
            Log.d(TAG, "Started ${flow.type} flow, asking: $question")
            FlowResult.AwaitingInput(question)
        }?.let { return it }

        return null
    }

    // ── Message flow start detection ───────────────────────────────────────────

    private val MESSAGE_TRIGGER_RE = Regex(
        """^(?:message|text|send\s+(?:a\s+)?(?:message|text)(?:\s+to)?|whatsapp|wa)\b""",
        RegexOption.IGNORE_CASE
    )

    private val MESSAGE_BODY_INLINE_RE = Regex(
        """(?:saying|to\s+say|and\s+say|that)\s+(.+)""",
        RegexOption.IGNORE_CASE
    )

    private fun detectMessageFlow(utterance: String): ActiveFlow? {
        if (!MESSAGE_TRIGGER_RE.containsMatchIn(utterance)) return null

        // If both contact AND body are present inline, ToolRegistry handles it
        val contactName = SlotExtractor.extractContactName(utterance)
        val hasBodyInline = MESSAGE_BODY_INLINE_RE.containsMatchIn(utterance)
        if (contactName != null && hasBodyInline) return null

        val lower = utterance.lowercase()
        // "WhatsApp Chris" always means WhatsApp regardless of the default setting.
        // Otherwise honour the user's preferred default channel (sms / whatsapp / ask).
        val explicitWhatsApp = lower.contains("whatsapp") || lower.startsWith("wa ")
        val defaultChannel   = settings?.defaultMsgChannel ?: "ask"
        val channel = when {
            explicitWhatsApp         -> "whatsapp"
            defaultChannel == "ask"  -> null   // will be asked below
            else                     -> defaultChannel
        }

        val missingSlots = ClarificationManager.initialMissingSlots(FlowType.MESSAGE_DRAFT)
        // When the default is "ask", add MESSAGE_CHANNEL to required slots so the
        // clarification loop will ask "SMS or WhatsApp?" before sending.
        if (channel == null) missingSlots.addFirst(SlotKey.MESSAGE_CHANNEL)

        val flow = ActiveFlow(
            type         = FlowType.MESSAGE_DRAFT,
            expiresAt    = ExpiryPolicy.expiresAt(FlowType.MESSAGE_DRAFT),
            missingSlots = missingSlots
        )
        if (channel != null) flow.fillSlot(SlotKey.MESSAGE_CHANNEL, channel)

        if (contactName != null) {
            flow.fillSlot(SlotKey.TARGET_CONTACT, contactName)
            entityTracker.track(EntityReference(EntityType.CONTACT, label = contactName))
        }

        return flow
    }

    // ── Reminder flow start detection ──────────────────────────────────────────

    private val REMINDER_TRIGGER_RE = Regex(
        """^(?:remind\s+me|set\s+(?:a\s+)?reminder|create\s+(?:a\s+)?reminder|add\s+(?:a\s+)?reminder|reminder\s+(?:to|for|about|at|in))\b""",
        RegexOption.IGNORE_CASE
    )

    private val REMINDER_CONTENT_ONLY_RE = Regex(
        """(?:remind\s+me\s+(?:to|about)|reminder\s+(?:to|about|for))\s+(.+)""",
        RegexOption.IGNORE_CASE
    )

    private fun detectReminderFlow(utterance: String): ActiveFlow? {
        if (!REMINDER_TRIGGER_RE.containsMatchIn(utterance)) return null

        val flow = ActiveFlow(
            type        = FlowType.REMINDER_CREATION,
            expiresAt   = ExpiryPolicy.expiresAt(FlowType.REMINDER_CREATION),
            missingSlots = ClarificationManager.initialMissingSlots(FlowType.REMINDER_CREATION)
        )

        // Try to extract partial info already present in the utterance
        val parsed = ReminderParser.parse(utterance)
        if (parsed != null) {
            flow.fillSlot(SlotKey.TRIGGER_TIME, parsed.triggerAtMs.toString())
            if (parsed.label.isNotBlank() && parsed.label != "reminder") {
                flow.fillSlot(SlotKey.REMINDER_CONTENT, parsed.label)
            }
        } else {
            // "tomorrow" without a clock time — store as a date hint
            if (utterance.lowercase().contains("tomorrow")) {
                flow.fillSlot(SlotKey.TRIGGER_DATE_HINT, "tomorrow")
            }
            // "remind me to X" / "reminder about X" — extract content only
            REMINDER_CONTENT_ONLY_RE.find(utterance)?.let { m ->
                val content = m.groupValues[1].trim()
                if (content.isNotBlank()) flow.fillSlot(SlotKey.REMINDER_CONTENT, content)
            }
        }

        return flow
    }

    // ── Call flow start detection ──────────────────────────────────────────────

    private val CALL_TRIGGER_RE = Regex(
        """^(?:call|phone|ring|dial)\b""",
        RegexOption.IGNORE_CASE
    )

    private fun detectCallFlow(utterance: String): ActiveFlow? {
        if (!CALL_TRIGGER_RE.containsMatchIn(utterance)) return null

        val contactName = SlotExtractor.extractContactName(utterance)

        val flow = ActiveFlow(
            type         = FlowType.CALL_CONTACT,
            expiresAt    = ExpiryPolicy.expiresAt(FlowType.CALL_CONTACT),
            missingSlots = ClarificationManager.initialMissingSlots(FlowType.CALL_CONTACT)
        )

        if (contactName != null) {
            flow.fillSlot(SlotKey.TARGET_CONTACT, contactName)
            entityTracker.track(EntityReference(EntityType.CONTACT, label = contactName))
            // ContactLookup.find() already resolves to a single number — no need to ask
            // for phone type. Filling TARGET_CONTACT completes all required slots so
            // tryStartFlow returns null and CallTool in ToolRegistry handles it directly.
        }

        return flow
    }

    // ── Flow execution ─────────────────────────────────────────────────────────

    private suspend fun executeFlow(flow: ActiveFlow): FlowResult {
        Log.d(TAG, "Executing ${flow.type} flow")
        return when (flow.type) {
            FlowType.MESSAGE_DRAFT    -> executeSms(flow)
            FlowType.EMAIL_DRAFT      -> executeEmail(flow)
            FlowType.CALL_CONTACT     -> executeCall(flow)
            FlowType.REMINDER_CREATION -> executeReminder(flow)
            FlowType.TIMER_CREATION   -> executeTimerFlow(flow)
            FlowType.APP_LAUNCH       -> executeAppLaunchFlow(flow)
            FlowType.CLARIFICATION    -> {
                // CLARIFICATION flows are resolved by the caller via the slot system;
                // if we somehow end up here all slots are filled — just complete cleanly.
                flow.markCompleted()
                flowContext.setActiveFlow(null)
                FlowResult.Complete(flow.lastPrompt ?: "Got it.")
            }
        }
    }

    // ── SMS / WhatsApp execution ───────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun executeSms(flow: ActiveFlow): FlowResult {
        val contactName = flow.slot(SlotKey.TARGET_CONTACT)
            ?: return FlowResult.AwaitingInput("Who should I message?")
        val body = flow.slot(SlotKey.MESSAGE_BODY)
            ?: return FlowResult.AwaitingInput("What do you want to say?")
        val channel = flow.slot(SlotKey.MESSAGE_CHANNEL) ?: "sms"

        val contact = contactLookup.find(contactName) ?: run {
            flow.markCompleted()
            flowContext.setActiveFlow(null)
            return FlowResult.Complete("No $contactName in your contacts that I can see.")
        }

        return try {
            if (channel == "whatsapp") {
                // whatsapp://send opens the conversation directly with the message pre-filled.
                // Auto-sending is not possible via Android intents — user must tap Send.
                val clean = contact.number.replace(Regex("[^\\d+]"), "")
                    .let { if (it.startsWith("+")) it.substring(1) else it }
                val uri = Uri.parse("whatsapp://send?phone=$clean&text=${Uri.encode(body)}")
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, uri)
                        .setPackage("com.whatsapp")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                flow.markCompleted("WhatsApp → ${contact.displayName}")
            } else {
                val sms = SmsManager.getDefault()
                sms.sendMultipartTextMessage(contact.number, null, sms.divideMessage(body), null, null)
                flow.markCompleted("SMS → ${contact.displayName}")
            }
            flowContext.setActiveFlow(null)
            val confirmation = if (channel == "whatsapp")
                "WhatsApp is ready — tap Send to confirm."
            else
                "Message sent to ${contact.displayName}."
            FlowResult.Complete(confirmation)
        } catch (e: Exception) {
            Log.e(TAG, "SMS execution failed: ${e.message}", e)
            flow.markCompleted()
            flowContext.setActiveFlow(null)
            FlowResult.Complete("That didn't go through. Want me to try again?")
        }
    }

    // ── Call execution ─────────────────────────────────────────────────────────

    private fun executeCall(flow: ActiveFlow): FlowResult {
        val contactName = flow.slot(SlotKey.TARGET_CONTACT)
            ?: return FlowResult.AwaitingInput("Who do you want to call?")

        val contact = contactLookup.find(contactName) ?: run {
            flow.markCompleted()
            flowContext.setActiveFlow(null)
            return FlowResult.Complete("No $contactName in your contacts that I can see.")
        }

        return try {
            context.startActivity(
                Intent(Intent.ACTION_CALL, Uri.parse("tel:${contact.number}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            flow.markCompleted("Call → ${contact.displayName}")
            flowContext.setActiveFlow(null)
            FlowResult.Complete("Calling ${contact.displayName}.")
        } catch (e: Exception) {
            Log.e(TAG, "Call execution failed: ${e.message}", e)
            flow.markCompleted()
            flowContext.setActiveFlow(null)
            FlowResult.Complete("I couldn't place the call.")
        }
    }

    // ── Reminder execution ─────────────────────────────────────────────────────

    private suspend fun executeReminder(flow: ActiveFlow): FlowResult {
        val content = flow.slot(SlotKey.REMINDER_CONTENT)
            ?: return FlowResult.AwaitingInput("What should I remind you about?")
        val triggerStr = flow.slot(SlotKey.TRIGGER_TIME)
            ?: return FlowResult.AwaitingInput("When?")
        val triggerMs = triggerStr.toLongOrNull()
            ?: return FlowResult.AwaitingInput("I didn't catch the time. When?")

        reminderRepository.create(
            label       = content,
            triggerAtMs = triggerMs,
            type        = ScheduledItemType.REMINDER
        )

        flow.markCompleted()
        flowContext.setActiveFlow(null)
        return FlowResult.Complete(formatReminderConfirmation(content, triggerMs))
    }

    // ── Timer flow detection ───────────────────────────────────────────────────

    /**
     * Triggers when the user asks for a timer but hasn't specified a duration.
     * Fully-specified commands like "set a timer for 5 minutes" are left to
     * TimerTool in ToolRegistry; we only intercept the bare/underspecified cases.
     */
    private val TIMER_TRIGGER_RE = Regex(
        """(?:set|start|create)\s+(?:a\s+)?timer\b|timer\s+(?:for|of)\s*$|countdown\s+(?:for|of)\s*$""",
        RegexOption.IGNORE_CASE
    )

    /** Duration patterns used to decide whether a duration is already present. */
    private val DURATION_PRESENT_RE = Regex(
        """\d+\s*(?:hour|hr|minute|min|second|sec)""",
        RegexOption.IGNORE_CASE
    )

    private fun detectTimerFlow(utterance: String): ActiveFlow? {
        val lower = utterance.lowercase()
        val isTrigger = lower.contains("set a timer") ||
            lower.contains("start a timer") ||
            lower.contains("timer for") ||
            lower.contains("countdown for")
        if (!isTrigger) return null

        // If a numeric duration is already present, let TimerTool handle it
        if (DURATION_PRESENT_RE.containsMatchIn(utterance)) return null

        return ActiveFlow(
            type         = FlowType.TIMER_CREATION,
            expiresAt    = ExpiryPolicy.expiresAt(FlowType.TIMER_CREATION),
            missingSlots = ClarificationManager.initialMissingSlots(FlowType.TIMER_CREATION)
        )
    }

    // ── Timer flow execution ───────────────────────────────────────────────────

    private fun executeTimerFlow(flow: ActiveFlow): FlowResult {
        val durationRaw = flow.slot(SlotKey.TRIGGER_TIME)
            ?: return FlowResult.AwaitingInput("How long?")
        val seconds = parseDurationToSeconds(durationRaw)
        if (seconds <= 0) return FlowResult.AwaitingInput("How long should the timer be?")

        val label = flow.slot(SlotKey.SUBJECT) ?: "Timer"

        return try {
            context.startActivity(
                Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            flow.markCompleted("Timer $seconds s")
            flowContext.setActiveFlow(null)
            FlowResult.Complete(
                if (seconds < 90) "Timer set for $seconds seconds."
                else "Timer set for ${seconds / 60} minutes."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Timer intent failed: ${e.message}", e)
            flow.markCompleted()
            flowContext.setActiveFlow(null)
            FlowResult.Complete("I couldn't set the timer.")
        }
    }

    /** Parses human-readable duration text into a total number of seconds. */
    private fun parseDurationToSeconds(s: String): Int {
        val c = s.trim().lowercase()
        var total = 0
        Regex("""(\d+)\s*hour""").find(c)?.let  { total += it.groupValues[1].toInt() * 3600 }
        Regex("""(\d+)\s*min""").find(c)?.let   { total += it.groupValues[1].toInt() * 60 }
        Regex("""(\d+)\s*sec""").find(c)?.let   { total += it.groupValues[1].toInt() }
        // bare number → treat as minutes
        if (total == 0) Regex("""^(\d+)$""").find(c.trim())?.let { total = it.groupValues[1].toInt() * 60 }
        return total
    }

    // ── App launch flow detection ──────────────────────────────────────────────

    /**
     * Triggers when the user says something like "open an app" or "launch
     * something" without naming a specific installed app.  Fully-specified
     * commands like "open Spotify" are claimed by OpenAppTool first.
     */
    private val APP_LAUNCH_VAGUE_RE = Regex(
        """^(?:open|launch|start)\s+(?:an?\s+app|something|an?\s+application)\b""",
        RegexOption.IGNORE_CASE
    )

    private fun detectAppLaunchFlow(utterance: String): ActiveFlow? {
        if (!APP_LAUNCH_VAGUE_RE.containsMatchIn(utterance)) return null

        return ActiveFlow(
            type         = FlowType.APP_LAUNCH,
            expiresAt    = ExpiryPolicy.expiresAt(FlowType.APP_LAUNCH),
            missingSlots = ClarificationManager.initialMissingSlots(FlowType.APP_LAUNCH)
        )
    }

    // ── App launch flow execution ──────────────────────────────────────────────

    private fun executeAppLaunchFlow(flow: ActiveFlow): FlowResult {
        val appName = flow.slot(SlotKey.APP_NAME)
            ?: return FlowResult.AwaitingInput("Which app?")

        val resolved = appResolver.resolve(appName)
        if (resolved is AppResolver.Result.NotFound) {
            flow.markCompleted()
            flowContext.setActiveFlow(null)
            return FlowResult.Complete("Hmm, I don't see $appName on your phone.")
        }

        return try {
            val pm = context.packageManager
            when (resolved) {
                is AppResolver.Result.Launchable -> {
                    val intent = pm.getLaunchIntentForPackage(resolved.packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?: run {
                            flow.markCompleted()
                            flowContext.setActiveFlow(null)
                            return FlowResult.Complete(
                                "I found ${resolved.displayLabel} but couldn't launch it."
                            )
                        }
                    context.startActivity(intent)
                    appResolver.rememberAlias(appName, resolved)
                    flow.markCompleted("Launched ${resolved.displayLabel}")
                    flowContext.setActiveFlow(null)
                    FlowResult.Complete("Opening ${resolved.displayLabel}.")
                }
                is AppResolver.Result.GenericIntent -> {
                    resolved.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(resolved.intent)
                    flow.markCompleted("Launched ${resolved.displayLabel}")
                    flowContext.setActiveFlow(null)
                    FlowResult.Complete("Opening ${resolved.displayLabel}.")
                }
                AppResolver.Result.NotFound -> {
                    // Already handled above — unreachable
                    flow.markCompleted()
                    flowContext.setActiveFlow(null)
                    FlowResult.Complete("Hmm, I don't see $appName on your phone.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "App launch failed: ${e.message}", e)
            flow.markCompleted()
            flowContext.setActiveFlow(null)
            FlowResult.Complete("That didn't work — try me again?")
        }
    }

    // ── Clarification flow factory ─────────────────────────────────────────────

    /**
     * Creates and activates a CLARIFICATION flow for a yes/no (or open-ended)
     * question that the LLM has already posed.  The [prompt] is stored in
     * [ActiveFlow.lastPrompt] so [ClarificationManager.nextQuestion] can
     * replay it if the user says "no" or is unclear.
     *
     * Call this immediately after surfacing the LLM's question to the user so
     * that the next utterance is routed through the follow-up system.
     */
    fun startClarificationFlow(prompt: String): ActiveFlow {
        val flow = ActiveFlow(
            type         = FlowType.CLARIFICATION,
            expiresAt    = ExpiryPolicy.expiresAt(FlowType.CLARIFICATION),
            missingSlots = ClarificationManager.initialMissingSlots(FlowType.CLARIFICATION),
            lastPrompt   = prompt
        )
        flowContext.setActiveFlow(flow)
        persistence.saveFlow(flow)
        Log.d(TAG, "Started CLARIFICATION flow: $prompt")
        return flow
    }

    // ── Email flow start detection ─────────────────────────────────────────────

    private val EMAIL_TRIGGER_RE = Regex(
        """^(?:send\s+(?:an?\s+)?email(?:\s+to)?|email|compose(?:\s+(?:an?\s+)?email(?:\s+to)?)?)\b""",
        RegexOption.IGNORE_CASE
    )

    // Matches: "email john@example.com" / "send an email to bob@work.com"
    private val EMAIL_ADDRESS_INLINE_RE = Regex(
        """[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""",
        RegexOption.IGNORE_CASE
    )

    private fun detectEmailFlow(utterance: String): ActiveFlow? {
        if (!EMAIL_TRIGGER_RE.containsMatchIn(utterance)) return null

        val flow = ActiveFlow(
            type         = FlowType.EMAIL_DRAFT,
            expiresAt    = ExpiryPolicy.expiresAt(FlowType.EMAIL_DRAFT),
            missingSlots = ClarificationManager.initialMissingSlots(FlowType.EMAIL_DRAFT)
        )

        // Extract inline email address if present
        EMAIL_ADDRESS_INLINE_RE.find(utterance)?.let { m ->
            flow.fillSlot(SlotKey.EMAIL_ADDRESS, m.value)
        }

        // Extract inline subject after "about" / "regarding" / "re:"
        Regex("""(?:about|regarding|re:|subject[:\s]+)\s*(.+)$""", RegexOption.IGNORE_CASE)
            .find(utterance)?.let { m ->
                val subject = m.groupValues[1].trim()
                if (subject.isNotBlank()) flow.fillSlot(SlotKey.EMAIL_SUBJECT, subject)
            }

        return flow
    }

    // ── Email execution ────────────────────────────────────────────────────────

    private fun executeEmail(flow: ActiveFlow): FlowResult {
        val rawAddress = flow.slot(SlotKey.EMAIL_ADDRESS)
            ?: return FlowResult.AwaitingInput("Who's the email address, or say a contact name?")
        val subject = flow.slot(SlotKey.EMAIL_SUBJECT)
            ?: return FlowResult.AwaitingInput("What's the subject?")
        val body = flow.slot(SlotKey.MESSAGE_BODY)
            ?: return FlowResult.AwaitingInput("What do you want to say?")

        // Resolve: if it looks like an email use it directly, otherwise look up in contacts
        val emailAddress = if (EMAIL_ADDRESS_INLINE_RE.matches(rawAddress)) {
            rawAddress
        } else {
            resolveContactEmail(rawAddress) ?: run {
                flow.markCompleted()
                flowContext.setActiveFlow(null)
                return FlowResult.Complete("I couldn't find an email address for $rawAddress.")
            }
        }

        return try {
            context.startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$emailAddress")).apply {
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            flow.markCompleted("Email → $emailAddress")
            flowContext.setActiveFlow(null)
            FlowResult.Complete("Email ready — tap Send to confirm.")
        } catch (e: Exception) {
            Log.e(TAG, "Email intent failed: ${e.message}", e)
            flow.markCompleted()
            flowContext.setActiveFlow(null)
            FlowResult.Complete("I couldn't open the email app.")
        }
    }

    /**
     * Look up a contact's first email address by display name.
     * Returns null if the contact has no email or isn't found.
     */
    private fun resolveContactEmail(name: String): String? = try {
        val cursor = context.contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Email.ADDRESS,
                android.provider.ContactsContract.CommonDataKinds.Email.DISPLAY_NAME
            ),
            "${android.provider.ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst())
                it.getString(it.getColumnIndexOrThrow(
                    android.provider.ContactsContract.CommonDataKinds.Email.ADDRESS))
            else null
        }
    } catch (_: Exception) { null }

    // ── Formatting helpers ─────────────────────────────────────────────────────

    private fun formatReminderConfirmation(content: String, triggerMs: Long): String {
        val diff = triggerMs - System.currentTimeMillis()
        return when {
            diff < 90_000L      -> "Reminder set in ${diff / 1_000} seconds: $content."
            diff < 3_600_000L   -> "Reminder set in ${diff / 60_000} minutes: $content."
            diff < 86_400_000L  -> {
                val t = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(triggerMs))
                "Reminder at $t: $content."
            }
            else -> {
                val t = SimpleDateFormat("EEE 'at' h:mm a", Locale.getDefault()).format(Date(triggerMs))
                "Reminder $t: $content."
            }
        }
    }
}
