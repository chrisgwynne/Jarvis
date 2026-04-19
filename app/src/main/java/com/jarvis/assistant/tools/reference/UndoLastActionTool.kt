package com.jarvis.assistant.tools.reference

import android.util.Log
import com.jarvis.assistant.runtime.plan.PlanRunner
import com.jarvis.assistant.runtime.reference.LastAction
import com.jarvis.assistant.runtime.reference.LastActionStore
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolRegistry
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * UndoLastActionTool — reverses the most recent reversible action recorded
 * in [LastActionStore].  Resolves "undo that" / "undo it" / "take that back".
 *
 * Delegation:
 *  - [LastAction.PlanRun]  → [PlanRunner.undoLastPlan]
 *  - [LastAction.ToolCall] → tool-local [Tool.undo] via the [ToolRegistry]
 *
 * Returns a friendly "nothing to undo" failure when the buffer has no live
 * reversible entry.  Never throws.
 */
class UndoLastActionTool(
    private val store: LastActionStore
) : Tool {

    /**
     * Set by [com.jarvis.assistant.runtime.JarvisRuntime] after the registry
     * and plan runner are constructed (which happens after this tool).  Kept
     * as plain setters rather than providers to avoid an extra lambda layer.
     */
    @Volatile var planRunner: PlanRunner? = null
    @Volatile var registry: ToolRegistry? = null

    override val name = "undo_last_action"
    override val description = "Undo the most recent reversible action"
    override val requiresNetwork = false

    override fun schema() = ToolSchema(
        name = name,
        description = "Undo or reverse the most recent action Jarvis just did. " +
            "Use when the user says \"undo that\", \"take that back\", \"nevermind, reverse it\".",
        parameters = mapOf(
            "type" to "object",
            "properties" to emptyMap<String, Any>(),
            "required" to emptyList<String>()
        )
    )

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim().lowercase()
        return if (UNDO_REGEX.containsMatchIn(t)) ToolInput(transcript) else null
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val entry = store.mostRecentReversible()
            ?: return ToolResult.Failure(spokenFeedback = "Nothing to undo.")

        return when (entry) {
            is LastAction.PlanRun -> undoPlan(entry)
            is LastAction.ToolCall -> undoToolCall(entry)
        }
    }

    private suspend fun undoPlan(entry: LastAction.PlanRun): ToolResult {
        val runner = planRunner
            ?: return ToolResult.Failure("Can't undo — plan runner isn't available.")
        val result = runner.undoLastPlan()
        return when (result) {
            is PlanRunner.UndoResult.Done    -> ToolResult.Success(spokenFeedback = result.spoken)
            is PlanRunner.UndoResult.TooOld  -> ToolResult.Failure("Too long ago to undo.")
            PlanRunner.UndoResult.Nothing    -> ToolResult.Failure("Nothing to undo.")
        }
    }

    private suspend fun undoToolCall(entry: LastAction.ToolCall): ToolResult {
        val reg = registry
            ?: return ToolResult.Failure("Can't undo right now.")
        val tool = reg.findByName(entry.toolName)
            ?: return ToolResult.Failure("That one doesn't reverse.")
        if (!tool.isReversible) {
            return ToolResult.Failure("That one doesn't reverse.")
        }
        return try {
            val argsMap = parseArgs(entry.argsJson)
            val result = tool.undo(ToolInput(entry.originatingTranscript, argsMap), entry.rawData)
            if (result is ToolResult.Success) {
                ToolResult.Success(
                    spokenFeedback = result.spokenFeedback.ifBlank { "Undone." }
                )
            } else {
                ToolResult.Failure(spokenFeedback = "That didn't undo cleanly.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "undo threw: ${e.message}")
            ToolResult.Failure("That didn't undo cleanly.")
        }
    }

    private fun parseArgs(argsJson: String): Map<String, String> = try {
        if (argsJson.isBlank()) emptyMap()
        else {
            @Suppress("UNCHECKED_CAST")
            (com.jarvis.assistant.llm.NetworkClient.gson.fromJson(argsJson, Map::class.java) as Map<*, *>)
                .entries.associate { (k, v) -> k.toString() to v.toString() }
        }
    } catch (e: Exception) {
        emptyMap()
    }

    companion object {
        private const val TAG = "UndoLastActionTool"
        private val UNDO_REGEX = Regex(
            """\b(?:undo (?:that|it|the last)|take that back|nevermind(?:,?\s*reverse it)?|reverse (?:that|it))\b""",
            RegexOption.IGNORE_CASE
        )
    }
}
