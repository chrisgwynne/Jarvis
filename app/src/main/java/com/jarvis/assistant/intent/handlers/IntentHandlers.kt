package com.jarvis.assistant.intent.handlers

import android.util.Log
import com.jarvis.assistant.intent.CommandEnvelope
import com.jarvis.assistant.intent.ContextResolver
import com.jarvis.assistant.intent.IntentModifier
import com.jarvis.assistant.intent.PrimaryIntent
import com.jarvis.assistant.llm.LlmRouter
import com.jarvis.assistant.llm.Message
import com.jarvis.assistant.memory.db.dao.MemoryDao
import com.jarvis.assistant.memory.db.entity.MemoryEntry
import com.jarvis.assistant.memory.db.entity.MemoryType
import com.jarvis.assistant.vision.ScreenObservationRepository
import com.jarvis.assistant.vision.ScreenObservationRetriever
import com.jarvis.assistant.vision.ScreenshotCaptureService
import com.jarvis.assistant.vision.VisionScreenAnalyzer

/**
 * ObserveScreenHandler — delegates to the existing look-at-this pipeline:
 * capture → analyse → (maybe) persist. Always speaks a one-sentence summary.
 */
class ObserveScreenHandler(
    private val screenshotCapture: ScreenshotCaptureService,
    private val analyzer:          VisionScreenAnalyzer,
    private val repository:        ScreenObservationRepository,
) : IntentHandler {
    override val intent = PrimaryIntent.OBSERVE_SCREEN

    override suspend fun handle(envelope: CommandEnvelope): HandlerResult {
        val capture = when (val r = screenshotCapture.capture()) {
            is ScreenshotCaptureService.Result.Success -> r
            is ScreenshotCaptureService.Result.Unavailable ->
                return HandlerResult.Failure("I can't see your screen right now.")
            is ScreenshotCaptureService.Result.Failure ->
                return HandlerResult.Failure("Something blocked the screenshot.")
        }
        val analysis = when (val r = analyzer.analyze(capture.file)) {
            is VisionScreenAnalyzer.Result.Success -> r.analysis
            is VisionScreenAnalyzer.Result.Failure -> return HandlerResult.Failure(r.reason)
        }
        // Honour the compose-on STORE_RESULT modifier by always saving when
        // it's present, otherwise defer to the repository's normal gates.
        val forceStore = IntentModifier.STORE_RESULT in envelope.modifiers
        if (forceStore || !analysis.sensitive) {
            repository.save(
                analysis           = analysis,
                screenshotPath     = capture.file.absolutePath,
                foregroundPackage  = capture.snapshot.foregroundPackage,
                capturedAtMs       = capture.snapshot.capturedAtMs,
            )
        }
        return HandlerResult.Analysed(
            summary         = analysis.summary.ifBlank { "Had a look." },
            screenshotPath  = capture.file.absolutePath,
        )
    }
}

/**
 * ActOnContextHandler — resolves "this" via [ContextResolver] and hands a
 * structured execution request back to the caller. The caller wires this
 * into the existing PlanRunner / Tool pipeline; we don't duplicate that
 * plumbing here.
 *
 * This handler NEVER fires state-changing work on its own. Confirmation is
 * already demanded by [com.jarvis.assistant.intent.RiskEvaluator]; a
 * follow-up execution pass happens only after the user confirms.
 */
class ActOnContextHandler(
    private val contextResolver: ContextResolver,
) : IntentHandler {
    override val intent = PrimaryIntent.ACT_ON_CONTEXT

    override suspend fun handle(envelope: CommandEnvelope): HandlerResult {
        val referent = contextResolver.resolveReferent(envelope.resolvedContext)
        if (referent.source == "none") {
            return HandlerResult.Failure(
                "I can't tell what you want me to act on. Select something first."
            )
        }
        return HandlerResult.Deferred(
            "Ready to act on $referent. Pending confirmation."
        )
    }
}

/**
 * StoreContextHandler — persists whatever "this" resolves to as a MemoryEntry.
 * Uses the label from [CommandEnvelope.label] when present.
 */
class StoreContextHandler(
    private val contextResolver: ContextResolver,
    private val memoryDao:       MemoryDao,
) : IntentHandler {
    override val intent = PrimaryIntent.STORE_CONTEXT

    companion object { private const val TAG = "StoreContextHandler" }

    override suspend fun handle(envelope: CommandEnvelope): HandlerResult {
        val referent = contextResolver.resolveReferent(envelope.resolvedContext)
        val body = referent.text
            ?: return HandlerResult.Failure("Nothing to save — try selecting or copying it first.")

        val label = envelope.label?.trim()?.ifBlank { null }
        val keywords = (listOfNotNull(label, referent.source) +
            body.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length in 3..20 })
            .distinct()
            .take(12)
            .joinToString(",")

        val content = buildString {
            if (label != null) { append("Label: "); append(label); append(" | ") }
            append("Source: "); append(referent.source); append(" | ")
            append(body.take(2_000))
        }
        val id = memoryDao.insert(
            MemoryEntry(
                type             = MemoryType.EPISODIC,
                content          = content,
                keywords         = keywords,
                importanceScore  = if (label != null) 0.6f else 0.4f,
            )
        )
        Log.d(TAG, "Stored context as memory id=$id label=$label source=${referent.source}")
        return HandlerResult.StoredMemory(memoryId = id, label = label)
    }
}

/**
 * RecallRecentContextHandler — pulls recent SCREEN_OBSERVATION + EPISODIC
 * rows and returns short summaries. The dialog controller threads these
 * into the spoken reply.
 */
class RecallRecentContextHandler(
    private val observationRetriever: ScreenObservationRetriever,
    private val memoryDao:            MemoryDao,
) : IntentHandler {
    override val intent = PrimaryIntent.RECALL_RECENT_CONTEXT

    override suspend fun handle(envelope: CommandEnvelope): HandlerResult {
        val observations = runCatching { observationRetriever.recent(limit = 3) }
            .getOrDefault(emptyList())
            .map { "${it.analysis.appName}: ${it.analysis.summary}" }
        val episodes = runCatching {
            memoryDao.getByType(MemoryType.EPISODIC, limit = 3)
        }.getOrDefault(emptyList())
            .map { it.content.take(160) }

        val summaries = (observations + episodes).distinct().take(4)
        if (summaries.isEmpty()) {
            return HandlerResult.Spoken("I don't have anything recent to go on.")
        }
        return HandlerResult.Recalled(summaries)
    }
}

/**
 * DraftReplyHandler — composes a draft reply via the main LLM pipeline.
 * REWRITE_MODE (from "say this better") rewrites the current input field
 * instead of drafting against an inbound message.
 */
class DraftReplyHandler(
    private val llmRouter:       LlmRouter,
    private val contextResolver: ContextResolver,
) : IntentHandler {
    override val intent = PrimaryIntent.DRAFT_REPLY

    override suspend fun handle(envelope: CommandEnvelope): HandlerResult {
        val referent = contextResolver.resolveReferent(envelope.resolvedContext)
        val source = referent.text
            ?: return HandlerResult.Failure(
                "I need something to reply to — select the message first."
            )
        val rewrite = IntentModifier.REWRITE_MODE in envelope.modifiers
        val system = if (rewrite) {
            "You are Jarvis. Rewrite the user's draft so it is clearer and more natural. " +
            "Keep the same intent and tone. Return only the rewritten text."
        } else {
            "You are Jarvis. Draft a short, natural reply to the message the user is looking at. " +
            "Match their everyday voice. Return only the draft — no preamble, no quotes."
        }
        val user = if (rewrite) "Draft to rewrite:\n$source" else "Message to reply to:\n$source"
        val draft = try {
            llmRouter.completeSilent(listOf(Message("system", system), Message("user", user)))
        } catch (e: Exception) {
            return HandlerResult.Failure("Couldn't draft a reply just now.", cause = e)
        }
        return HandlerResult.Draft(text = draft.trim(), sourceInput = source)
    }
}

/** InterruptAssistantHandler — "stop". Returns a control signal; no TTS. */
class InterruptAssistantHandler : IntentHandler {
    override val intent = PrimaryIntent.INTERRUPT_ASSISTANT
    override suspend fun handle(envelope: CommandEnvelope): HandlerResult =
        HandlerResult.Control(ControlSignal.INTERRUPT)
}

/** PauseAssistantHandler — "wait". Returns a control signal; no TTS. */
class PauseAssistantHandler : IntentHandler {
    override val intent = PrimaryIntent.PAUSE_ASSISTANT
    override suspend fun handle(envelope: CommandEnvelope): HandlerResult =
        HandlerResult.Control(ControlSignal.PAUSE)
}

/** ResumeAssistantHandler — "carry on". Returns a control signal; no TTS. */
class ResumeAssistantHandler : IntentHandler {
    override val intent = PrimaryIntent.RESUME_ASSISTANT
    override suspend fun handle(envelope: CommandEnvelope): HandlerResult =
        HandlerResult.Control(ControlSignal.RESUME)
}

/**
 * ChangeResponseStyleHandler — maps the two style-modifier variants to the
 * corresponding control signal. STYLE_EXPANDED wins if both are present
 * (caller explicitly asked for more).
 */
class ChangeResponseStyleHandler : IntentHandler {
    override val intent = PrimaryIntent.CHANGE_RESPONSE_STYLE
    override suspend fun handle(envelope: CommandEnvelope): HandlerResult {
        val signal = when {
            IntentModifier.STYLE_EXPANDED in envelope.modifiers -> ControlSignal.STYLE_EXPANDED
            IntentModifier.STYLE_CONCISE  in envelope.modifiers -> ControlSignal.STYLE_CONCISE
            else -> return HandlerResult.Failure("I couldn't tell which style you want.")
        }
        return HandlerResult.Control(signal)
    }
}
