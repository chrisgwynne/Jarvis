package com.jarvis.assistant.tools.device

import com.jarvis.assistant.core.routines.RoutineRepository
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * ListRoutinesTool — speaks the user's saved routine names.
 * "what routines do I have", "list my routines", "what workflows do I
 * have saved".
 */
class ListRoutinesTool(
    private val repo: RoutineRepository,
) : Tool {
    override val name = "list_routines"
    override val description = "List saved routines by name"
    override val requiresNetwork = false
    override val isLocalFallback = true
    override val requiredPermissions: List<String> = emptyList()

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = "List all saved routines by name.",
        parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
    )

    override fun matches(transcript: String): ToolInput? {
        if (RE.containsMatchIn(transcript.trim().lowercase())) {
            return ToolInput(transcript = transcript)
        }
        return null
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val routines = repo.list()
        return if (routines.isEmpty()) {
            ToolResult.Success("No routines saved yet.")
        } else {
            val names = routines.joinToString(", ") { it.name }
            ToolResult.Success("You've got ${routines.size}: $names.")
        }
    }

    companion object {
        private val RE = Regex(
            """(?:list|what|which|show)\s+(?:are\s+)?(?:my\s+|the\s+)?(?:saved\s+)?(?:routines?|workflows?|flows?)"""
        )
    }
}
