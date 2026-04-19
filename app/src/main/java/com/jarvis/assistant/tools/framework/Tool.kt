package com.jarvis.assistant.tools.framework

/**
 * Tool — the single interface every Jarvis action implements.
 *
 * DESIGN INTENT:
 *   Each tool is self-describing: it knows its name, whether it needs the
 *   internet, which permissions it requires, and how to pattern-match a
 *   transcript.  The ToolRegistry calls matches() on each tool in order;
 *   the first match wins.
 *
 * ADDING A NEW TOOL:
 *   1. Create a class in tools/device/ or tools/web/ that implements Tool.
 *   2. Register it in ToolRegistry.buildDefault().
 *   That's it — no changes elsewhere.
 */
interface Tool {
    /** Machine name used in logs and DeviceStateStore.currentToolName. */
    val name: String

    /** Human-readable description shown in debug panel (not spoken). */
    val description: String

    /**
     * If true, this tool calls the internet. The registry skips it
     * when the device is offline (unless [isLocalFallback] is also true).
     */
    val requiresNetwork: Boolean get() = false

    /**
     * If true, this tool has a meaningful offline path and should never
     * be skipped, even when [requiresNetwork] is true and we're offline.
     */
    val isLocalFallback: Boolean get() = false

    /**
     * Android permission strings this tool needs.
     * Used for capability checks before execution.
     */
    val requiredPermissions: List<String> get() = emptyList()

    /**
     * Try to match [transcript] to this tool's pattern.
     * Returns a populated [ToolInput] if the tool can handle the transcript,
     * or null if it cannot.
     *
     * Must be fast — no I/O, no coroutines.
     */
    fun matches(transcript: String): ToolInput?

    /**
     * Execute the tool with the given [input].
     * Suspend-safe — may do I/O.
     * Must not throw; wrap errors in [ToolResult.Failure].
     */
    suspend fun execute(input: ToolInput): ToolResult

    /**
     * Return a [ToolSchema] if this tool supports LLM function calling.
     * Null means the tool is only reachable via the regex [matches] path.
     */
    fun schema(): ToolSchema? = null

    /**
     * True when this tool's effect can be reversed by [undo].  Plans that
     * include any irreversible step warn the user before executing.
     *
     * Default false — opt in by overriding.  A plain "Done." spoken reply is
     * not reversible; turning on the torch is; sending an SMS is not.
     */
    val isReversible: Boolean get() = false

    /**
     * Reverse the effect of a previously successful [execute] call.
     *
     * The [journal] is the post-execute state captured by the [PlanRunner]
     * (the [ToolResult.Success.rawData] payload, plus the original input).
     * Implementations parse [journal] to recover whatever they need (e.g.
     * the inserted reminder id) and undo it.
     *
     * Default implementation is a no-op success — appropriate for tools
     * whose execute() didn't change observable state.  Reversible tools
     * MUST override to actually revert and MUST also set [isReversible].
     */
    suspend fun undo(input: ToolInput, journal: String): ToolResult =
        ToolResult.Success(spokenFeedback = "")
}

/** Parsed input for a tool, with typed convenience fields. */
data class ToolInput(
    /** The original transcript that triggered this tool. */
    val transcript: String,
    /** Tool-specific named parameters extracted during matching. */
    val params: Map<String, String> = emptyMap()
) {
    fun param(key: String): String = params[key] ?: ""
    fun paramOrNull(key: String): String? = params[key]
}

/** Result of a tool execution. */
sealed class ToolResult {

    /**
     * The tool ran successfully.
     * [spokenFeedback] is spoken aloud as Jarvis's reply.
     * [requiresLlmFollowUp] = true if the result should be fed back to the LLM
     *   before speaking (e.g. a web search result that needs summarising).
     * [silent] = true suppresses TTS entirely and returns immediately to wake-word
     *   mode.  Use for actions where the user can hear the result themselves
     *   (media play/pause/skip) — speaking a confirmation would steal audio focus
     *   and interrupt the very media that was just started.
     */
    data class Success(
        val spokenFeedback: String,
        val rawData: String = "",
        val requiresLlmFollowUp: Boolean = false,
        val silent: Boolean = false
    ) : ToolResult()

    /**
     * The tool matched but failed.
     * [spokenFeedback] is spoken aloud so the user knows what happened.
     */
    data class Failure(
        val spokenFeedback: String,
        val cause: Throwable? = null
    ) : ToolResult()

    /**
     * The tool augmented the transcript with live data.
     * [augmentedTranscript] should be passed to the LLM instead of the original.
     */
    data class Augmented(val augmentedTranscript: String) : ToolResult()

    /** The tool did not match this transcript (should never reach callers). */
    object NotMatched : ToolResult()
}
