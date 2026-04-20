package com.jarvis.assistant.tools.device

import com.jarvis.assistant.core.routines.RecentToolCallBuffer
import com.jarvis.assistant.core.routines.RoutineRepository
import com.jarvis.assistant.runtime.plan.PlannedStep
import com.jarvis.assistant.tools.framework.RiskClass
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema
import com.jarvis.assistant.llm.NetworkClient

/**
 * SaveRoutineTool — promotes the user's recent successful tool calls into
 * a reusable [com.jarvis.assistant.core.routines.SavedRoutineEntity].
 *
 * Matches phrases like:
 *   "save that as a routine called morning coffee"
 *   "save those last three steps as evening wind down"
 *
 * Default capture window is the last 4 tool calls within the buffer's
 * TTL. Users can override via a number word ("last two", "last three").
 */
class SaveRoutineTool(
    private val buffer: RecentToolCallBuffer,
    private val repo: RoutineRepository,
) : Tool {
    override val name = "save_routine"
    override val description = "Save recent tool calls as a named reusable routine"
    override val requiresNetwork = false
    override val isLocalFallback = true
    override val requiredPermissions: List<String> = emptyList()
    override val riskClass = RiskClass.LOW

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = "Save the last N successful tool calls as a named routine.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name" to mapOf("type" to "string"),
                "stepCount" to mapOf("type" to "integer"),
            ),
            "required" to listOf("name"),
        ),
    )

    override fun matches(transcript: String): ToolInput? {
        val match = RE.find(transcript.trim().lowercase()) ?: return null
        val countWord = match.groupValues.getOrNull(1).orEmpty()
        val routineName = match.groupValues.getOrNull(2).orEmpty().trim()
        if (routineName.isBlank()) return null
        val count = countWord.toIntOrZeroOrDefault(4)
        return ToolInput(
            transcript = transcript,
            params = mapOf("name" to routineName, "stepCount" to count.toString()),
        )
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val routineName = input.param("name").ifBlank {
            return ToolResult.Failure("What should I call it?")
        }
        val count = input.param("stepCount").toIntOrNull() ?: 4
        val recent = buffer.lastN(count)
        if (recent.isEmpty()) return ToolResult.Failure("Nothing to save — no recent actions.")

        val steps = recent.mapIndexed { idx, entry ->
            PlannedStep(
                ordinal = idx,
                toolName = entry.toolName,
                argsJson = NetworkClient.gson.toJson(entry.params),
                shortLabel = entry.shortLabel,
                reversible = entry.reversible,
            )
        }
        val saved = repo.save(routineName, steps)
        val stepsLabel = if (saved.let { steps.size == 1 }) "one step" else "${steps.size} steps"
        return ToolResult.Success("Saved '${saved.name}' — $stepsLabel.")
    }

    private fun String.toIntOrZeroOrDefault(default: Int): Int = when (this) {
        "one" -> 1
        "two" -> 2
        "three" -> 3
        "four" -> 4
        "five" -> 5
        "" -> default
        else -> toIntOrNull() ?: default
    }

    companion object {
        // "save that|those|the last N|X as (a) routine called Y"
        // "save those last two as routine called morning coffee"
        private val RE = Regex(
            """save\s+(?:that|those|the\s+last\s+(one|two|three|four|five|\d+)?)\s*(?:steps?|actions?|things?)?\s+as\s+(?:a\s+)?routine(?:\s+called|\s+named)?\s+(.+?)\s*[.!?]?\s*$"""
        )
    }
}
