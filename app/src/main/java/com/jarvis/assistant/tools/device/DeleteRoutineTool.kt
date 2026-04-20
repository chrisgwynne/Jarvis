package com.jarvis.assistant.tools.device

import com.jarvis.assistant.core.routines.RoutineRepository
import com.jarvis.assistant.tools.framework.RiskClass
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * DeleteRoutineTool — removes a saved routine by name.
 * "delete morning coffee routine", "forget the wind down routine".
 *
 * MEDIUM risk: deleting a routine isn't a destructive action in the
 * outside world, but it's irrecoverable from the user's perspective so
 * we gate behind ConfirmationGate.
 */
class DeleteRoutineTool(
    private val repo: RoutineRepository,
) : Tool {
    override val name = "delete_routine"
    override val description = "Delete a saved routine by name"
    override val requiresNetwork = false
    override val isLocalFallback = true
    override val requiredPermissions: List<String> = emptyList()
    override val riskClass = RiskClass.MEDIUM

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = "Delete a saved routine by its name.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf("name" to mapOf("type" to "string")),
            "required" to listOf("name"),
        ),
    )

    override fun matches(transcript: String): ToolInput? {
        val match = RE.find(transcript.trim().lowercase()) ?: return null
        val routineName = (1..match.groupValues.lastIndex)
            .asSequence()
            .mapNotNull { match.groupValues.getOrNull(it)?.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: return null
        return ToolInput(transcript = transcript, params = mapOf("name" to routineName))
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val name = input.param("name").ifBlank { return ToolResult.Failure("Which one?") }
        val ok = repo.delete(name)
        return if (ok) ToolResult.Success("Deleted '$name'.")
        else ToolResult.Failure("No routine called '$name'.")
    }

    companion object {
        private val RE = Regex(
            """(?:delete|remove|forget|drop)\s+(?:my\s+|the\s+)?(.+?)\s+(?:routine|workflow|flow)\s*[.!?]?\s*$"""
        )
    }
}
