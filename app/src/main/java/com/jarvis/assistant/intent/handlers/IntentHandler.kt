package com.jarvis.assistant.intent.handlers

import com.jarvis.assistant.intent.CommandEnvelope
import com.jarvis.assistant.intent.PrimaryIntent

/**
 * IntentHandler — the execution side of a routed [CommandEnvelope].
 *
 * CONTRACT:
 *   * Each handler owns exactly one [PrimaryIntent] (see [intent]).
 *   * [handle] is suspend-safe; long IO is allowed.
 *   * Handlers do NOT perform confirmation themselves — a dialog
 *     controller up-stream reads envelope.requiresConfirmation and asks
 *     the user first when needed, THEN invokes the handler.
 *   * Return a [HandlerResult] so the caller can wire the result into TTS,
 *     ledger entries, etc. without parsing free-form strings.
 */
interface IntentHandler {
    val intent: PrimaryIntent
    suspend fun handle(envelope: CommandEnvelope): HandlerResult
}

/**
 * Typed result every handler returns.  Each variant carries the minimum
 * information the dispatch loop needs to continue:
 *
 *   Spoken           — plain spoken reply; also used for "I couldn't do that" errors.
 *   Deferred         — handler requires confirmation the router didn't demand
 *                      (rare; included for completeness).
 *   Control          — signals the dialog / TTS layer to mutate state (stop / pause / resume / style change).
 *   StoredMemory     — memory id that was written (for tracing and undo).
 *   Recalled         — list of recalled observations or episodes to stitch into the reply.
 *   Draft            — a drafted reply the UI can pre-fill into the current input field.
 *   Analysed         — screen-analysis result (delegates to the vision pipeline).
 *   Failure          — handler ran but couldn't complete its job; spokenFeedback suitable for TTS.
 */
sealed class HandlerResult {
    data class Spoken(val text: String) : HandlerResult()

    data class Deferred(val reason: String) : HandlerResult()

    data class Control(val signal: ControlSignal, val detail: String? = null) : HandlerResult()

    data class StoredMemory(val memoryId: Long, val label: String?) : HandlerResult()

    data class Recalled(val summaries: List<String>) : HandlerResult()

    data class Draft(val text: String, val sourceInput: String?) : HandlerResult()

    data class Analysed(val summary: String, val screenshotPath: String?) : HandlerResult()

    data class Failure(val spokenFeedback: String, val cause: Throwable? = null) : HandlerResult()
}

/**
 * Control signals the dialog / TTS layer understands.
 * Mapped 1:1 with the control-tier primary intents.
 */
enum class ControlSignal {
    INTERRUPT,
    PAUSE,
    RESUME,
    STYLE_CONCISE,
    STYLE_EXPANDED,
}
