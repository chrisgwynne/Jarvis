package com.jarvis.assistant.tools.device

import com.jarvis.assistant.core.routines.RoutineRepository
import com.jarvis.assistant.runtime.plan.PlanRunner
import com.jarvis.assistant.tools.framework.RiskClass
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * RunRoutineTool — executes a named [com.jarvis.assistant.core.routines
 * .SavedRoutineEntity] via [PlanRunner].
 *
 * Matches "run morning coffee routine", "do the evening wind down
 * workflow". Requires the explicit "routine/workflow/flow" keyword so
 * the regex can't swallow ordinary "run the app" / "do the dishes"
 * utterances.
 *
 * MEDIUM risk because a saved routine may contain message-sending steps
 * whose individual HIGH risk was already confirmed at creation. The plan
 * summary the runner speaks ("Running morning coffee.") plus the
 * ConfirmationGate prompt gives the user one more chance to abort.
 */
class RunRoutineTool(
    private val repo: RoutineRepository,
    private val planRunnerProvider: () -> PlanRunner?,
) : Tool {
    override val name = "run_routine"
    override val description = "Run a saved routine by name"
    override val requiresNetwork = false
    override val isLocalFallback = true
    override val requiredPermissions: List<String> = emptyList()
    override val riskClass = RiskClass.LOW

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = "Execute a previously saved routine by its name.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf("name" to mapOf("type" to "string")),
            "required" to listOf("name"),
        ),
    )

    override fun matches(transcript: String): ToolInput? {
        val text = transcript.trim().lowercase()
        val match = RE_KEYWORD_LAST.find(text) ?: RE_KEYWORD_FIRST.find(text) ?: return null
        val routineName = (1..match.groupValues.lastIndex)
            .asSequence()
            .mapNotNull { match.groupValues.getOrNull(it)?.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: return null
        return ToolInput(transcript = transcript, params = mapOf("name" to routineName))
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val requested = input.param("name").ifBlank {
            return ToolResult.Failure("Which routine?")
        }
        val runner = planRunnerProvider() ?: return ToolResult.Failure("Plan runner unavailable.")
        val entity = repo.findByName(requested) ?: return ToolResult.Failure("No routine called '$requested'.")
        val plan = repo.toPlan(entity, input.transcript) ?: return ToolResult.Failure("Couldn't load '${entity.name}'.")
        val result = runner.execute(plan)
        repo.markRun(entity.id)
        return when (result) {
            is PlanRunner.Resolution.Ran -> ToolResult.Success(result.spoken)
            is PlanRunner.Resolution.Halted -> ToolResult.Failure(result.spoken)
            is PlanRunner.Resolution.Cancelled -> ToolResult.Success(result.spoken)
        }
    }

    companion object {
        private val RE_KEYWORD_LAST = Regex(
            """(?:run|start|do|execute|play)\s+(?:my\s+|the\s+)?(.+?)\s+(?:routine|workflow|flow)\s*[.!?]?\s*$"""
        )
        private val RE_KEYWORD_FIRST = Regex(
            """(?:run|start|do|execute|play)\s+(?:routine|workflow|flow)\s+(.+?)\s*[.!?]?\s*$"""
        )
    }
}
