package com.jarvis.assistant.tools.device

import com.jarvis.assistant.shopping.ShoppingRepository
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult

class ShoppingListTool(private val repository: ShoppingRepository) : Tool {

    override val name = "shopping_list"
    override val description = "Manage a voice-controlled shopping list"
    override val requiresNetwork = false

    // Matches "add X to [my] [shopping] list", "buy X", "I need X"
    private val ADD_REGEX = Regex(
        """(?:add\s+(.+?)\s+to\s+(?:my\s+)?(?:shopping\s+)?list|buy\s+(.+)|i\s+need\s+(.+))""",
        RegexOption.IGNORE_CASE
    )

    // Matches "read [my] [shopping] list", "what's on my list", "shopping list"
    private val READ_REGEX = Regex(
        """(?:(?:read|show|tell me)\s+(?:my\s+)?(?:shopping\s+)?list|what(?:'s| is)\s+on\s+(?:my\s+)?(?:shopping\s+)?list|shopping\s+list)""",
        RegexOption.IGNORE_CASE
    )

    // Matches "remove X from [my] list", "I got X", "bought X"
    private val REMOVE_REGEX = Regex(
        """(?:remove\s+(.+?)\s+from\s+(?:my\s+)?(?:shopping\s+)?list|i\s+(?:got|have\s+got)\s+(.+)|bought\s+(.+))""",
        RegexOption.IGNORE_CASE
    )

    // Matches "clear [my] [shopping] list", "empty [the] list"
    private val CLEAR_REGEX = Regex(
        """(?:clear\s+(?:my\s+)?(?:shopping\s+)?list|empty\s+(?:the\s+)?(?:shopping\s+)?list)""",
        RegexOption.IGNORE_CASE
    )

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()

        ADD_REGEX.find(t)?.let { m ->
            val item = (m.groupValues[1].takeIf { it.isNotBlank() }
                ?: m.groupValues[2].takeIf { it.isNotBlank() }
                ?: m.groupValues[3]).trim()
            return ToolInput(transcript, mapOf("ACTION" to "add", "ITEM" to item))
        }

        REMOVE_REGEX.find(t)?.let { m ->
            val item = (m.groupValues[1].takeIf { it.isNotBlank() }
                ?: m.groupValues[2].takeIf { it.isNotBlank() }
                ?: m.groupValues[3]).trim()
            return ToolInput(transcript, mapOf("ACTION" to "remove", "ITEM" to item))
        }

        if (CLEAR_REGEX.containsMatchIn(t)) {
            return ToolInput(transcript, mapOf("ACTION" to "clear"))
        }

        if (READ_REGEX.containsMatchIn(t)) {
            return ToolInput(transcript, mapOf("ACTION" to "read"))
        }

        return null
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        return try {
            when (input.param("ACTION")) {
                "add" -> {
                    val item = input.param("ITEM")
                    repository.addItem(item)
                    ToolResult.Success("Added $item to your list.")
                }
                "read" -> {
                    val list = repository.getItems()
                    if (list.isEmpty()) {
                        ToolResult.Success("Your list is empty.")
                    } else {
                        val s = if (list.size == 1) "" else "s"
                        val joined = list.joinToString(", ") { it.item }
                        ToolResult.Success("You have ${list.size} item$s: $joined.")
                    }
                }
                "remove" -> {
                    val item = input.param("ITEM")
                    repository.markDone(item)
                    ToolResult.Success("Removed $item from your list.")
                }
                "clear" -> {
                    repository.clearAll()
                    ToolResult.Success("Shopping list cleared.")
                }
                else -> ToolResult.Failure("I didn't understand that shopping list command.")
            }
        } catch (e: Exception) {
            ToolResult.Failure("Something went wrong with the shopping list: ${e.message}", e)
        }
    }
}
