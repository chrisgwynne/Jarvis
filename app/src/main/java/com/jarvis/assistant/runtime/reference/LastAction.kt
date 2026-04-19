package com.jarvis.assistant.runtime.reference

/**
 * LastAction — a lightweight record of something the assistant just did,
 * held transiently so the user can say "do the same for X" or "undo that".
 *
 * Lives only in memory (see [LastActionStore]); referential pronouns are
 * inherently session-local and don't need to survive a process restart.
 */
sealed class LastAction {
    abstract val id: String
    abstract val createdAtMs: Long
    abstract val originatingTranscript: String
    /** Short noun-phrase used to disambiguate ("text", "reminder", "flashlight"). */
    abstract val shortLabel: String
    /** True when the action can be reversed via [ToolCall.toolName].undo or plan undo. */
    abstract val reversible: Boolean

    /** A single tool invocation (the common case). */
    data class ToolCall(
        override val id: String,
        override val createdAtMs: Long,
        override val originatingTranscript: String,
        override val shortLabel: String,
        override val reversible: Boolean,
        val toolName: String,
        val argsJson: String,
        /** Raw payload from ToolResult.Success, used for undo() reconstruction. */
        val rawData: String
    ) : LastAction()

    /** A multi-step plan executed by PlanRunner. */
    data class PlanRun(
        override val id: String,
        override val createdAtMs: Long,
        override val originatingTranscript: String,
        override val shortLabel: String,
        override val reversible: Boolean,
        val planId: String
    ) : LastAction()
}
