package com.jarvis.assistant.tools.reference

import android.util.Log
import com.jarvis.assistant.runtime.reference.LastAction
import com.jarvis.assistant.runtime.reference.LastActionStore
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolRegistry
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * RepeatLastActionTool — re-runs the most recent tool call, optionally with
 * a parameter substitution.  Resolves phrases like "do the same for Mike"
 * or "again, but louder".
 *
 * Scope is intentionally tight: we only repeat [LastAction.ToolCall] entries
 * (not plans) since plans require the full PlanRunner confirmation dance.
 * If the user wants to repeat a plan, the LLM can synthesise a fresh
 * MultiToolCall from the transcript.
 */
class RepeatLastActionTool(
    private val store: LastActionStore
) : Tool {

    @Volatile var registry: ToolRegistry? = null

    override val name = "repeat_last_action"
    override val description = "Repeat the most recent tool call, optionally substituting one parameter"
    override val requiresNetwork = false

    override fun schema() = ToolSchema(
        name = name,
        description = "Repeat the most recent action Jarvis just did. " +
            "Optional substitute.key + substitute.value replaces one parameter " +
            "(e.g. substitute.contact=\"Mike\"). Use when the user says " +
            "\"do the same for X\" or \"again\".",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "substituteKey" to mapOf(
                    "type" to "string",
                    "description" to "Parameter name to replace, or omit to repeat verbatim"
                ),
                "substituteValue" to mapOf(
                    "type" to "string",
                    "description" to "New value for [substituteKey]"
                )
            ),
            "required" to emptyList<String>()
        )
    )

    override fun matches(transcript: String): ToolInput? = null // LLM-only

    override suspend fun execute(input: ToolInput): ToolResult {
        val last = store.snapshot().firstOrNull { it is LastAction.ToolCall } as? LastAction.ToolCall
            ?: return ToolResult.Failure("Nothing recent to repeat.")
        val reg = registry
            ?: return ToolResult.Failure("Can't repeat right now.")
        val tool = reg.findByName(last.toolName)
            ?: return ToolResult.Failure("That tool isn't available anymore.")

        val substKey = input.paramOrNull("substituteKey")?.takeIf { it.isNotBlank() }
        val substValue = input.paramOrNull("substituteValue")?.takeIf { it.isNotBlank() }
        val newArgs = buildMap {
            putAll(parseArgs(last.argsJson))
            if (substKey != null && substValue != null) put(substKey, substValue)
        }

        return try {
            tool.execute(ToolInput(last.originatingTranscript, newArgs))
        } catch (e: Exception) {
            Log.w(TAG, "Repeat of ${last.toolName} threw: ${e.message}")
            ToolResult.Failure("That didn't go through.")
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
        private const val TAG = "RepeatLastActionTool"
    }
}
