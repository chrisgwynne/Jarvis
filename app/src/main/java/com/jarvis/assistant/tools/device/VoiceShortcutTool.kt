package com.jarvis.assistant.tools.device

import android.util.Log
import com.jarvis.assistant.shortcuts.VoiceShortcutRepository
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * VoiceShortcutTool — create, list, delete, and run named voice shortcut sequences.
 *
 * Supported phrases:
 *   "add shortcut morning routine: check weather, check calendar, read notifications"
 *   "run shortcut morning routine"
 *   "list my shortcuts"
 *   "delete shortcut morning routine"
 */
class VoiceShortcutTool(private val repository: VoiceShortcutRepository) : Tool {

    override val name            = "voice_shortcut"
    override val description     = "Create, run, list, and delete named voice command shortcuts"
    override val requiresNetwork = false

    override fun schema() = ToolSchema(
        name        = name,
        description = "Manage named voice command shortcuts: add, run, list, or delete.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "action"        to mapOf(
                    "type" to "string",
                    "enum" to listOf("add", "run", "list", "delete"),
                    "description" to "What to do"
                ),
                "shortcut_name" to mapOf("type" to "string", "description" to "Name of the shortcut (for add, run, delete)"),
                "commands"      to mapOf("type" to "string", "description" to "Comma-separated command list (for add only)")
            ),
            "required" to listOf("action")
        )
    )

    companion object {
        private const val TAG = "VoiceShortcutTool"

        private val ADD_PATTERN = Regex(
            """add\s+shortcut\s+(?:called\s+)?(.+?)[:]\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        private val RUN_PATTERN = Regex(
            """(?:run|execute|trigger)\s+shortcut\s+(.+)""",
            RegexOption.IGNORE_CASE
        )
        private val LIST_PATTERN = Regex(
            """list\s+(?:my\s+)?shortcuts?""",
            RegexOption.IGNORE_CASE
        )
        private val DELETE_PATTERN = Regex(
            """(?:delete|remove)\s+shortcut\s+(.+)""",
            RegexOption.IGNORE_CASE
        )
    }

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        return when {
            ADD_PATTERN.containsMatchIn(t)    -> ToolInput(t, mapOf("action" to "add"))
            LIST_PATTERN.containsMatchIn(t)   -> ToolInput(t, mapOf("action" to "list"))
            DELETE_PATTERN.containsMatchIn(t) -> ToolInput(t, mapOf("action" to "delete"))
            RUN_PATTERN.containsMatchIn(t)    -> ToolInput(t, mapOf("action" to "run"))
            else                               -> null
        }
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val t = input.transcript.trim()

        ADD_PATTERN.find(t)?.let { m ->
            val name     = m.groupValues[1].trim()
            val rawCmds  = m.groupValues[2].trim()
            val commands = rawCmds.split(Regex(""",\s*|\s+and\s+""")).filter { it.isNotBlank() }
            if (commands.isEmpty()) return ToolResult.Failure("Please specify at least one command for the shortcut.")
            repository.add(name, commands)
            Log.d(TAG, "Added shortcut '$name' with ${commands.size} commands")
            return ToolResult.Success(
                "Shortcut '$name' saved with ${commands.size} step${if (commands.size > 1) "s" else ""}."
            )
        }

        LIST_PATTERN.find(t)?.let {
            val all = repository.getAll()
            if (all.isEmpty()) return ToolResult.Success("You have no saved shortcuts yet.")
            val names = all.joinToString(", ") { "'${it.name}'" }
            return ToolResult.Success("Your shortcuts: $names.")
        }

        DELETE_PATTERN.find(t)?.let { m ->
            val name = m.groupValues[1].trim()
            val deleted = repository.delete(name)
            return if (deleted) ToolResult.Success("Shortcut '$name' deleted.")
            else ToolResult.Failure("No shortcut named '$name' found.")
        }

        RUN_PATTERN.find(t)?.let { m ->
            val name     = m.groupValues[1].trim()
            val shortcut = repository.findByTrigger(name)
                ?: return ToolResult.Failure("No shortcut named '$name' found. Say 'list shortcuts' to see what's available.")
            val commands = repository.commandsOf(shortcut)
            val spoken = commands.mapIndexed { i, cmd -> "${i + 1}. $cmd" }.joinToString(". ")
            Log.d(TAG, "Running shortcut '${shortcut.name}': $commands")
            return ToolResult.Success(
                "Running ${shortcut.name}: $spoken."
            )
        }

        return ToolResult.Failure("I didn't understand that shortcut command.")
    }
}
