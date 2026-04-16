package com.jarvis.assistant.tools.device

import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult

/**
 * HelpTool — answers "what can you do?" with a dynamically built capability list.
 *
 * [capabilityProvider] is a lambda supplied at construction time (from ToolRegistry)
 * that inspects live settings and registered tools.  This keeps the spoken answer
 * current without hardcoding a stale list here.
 */
class HelpTool(private val capabilityProvider: () -> String) : Tool {

    override val name = "help"
    override val description = "Lists what Jarvis can currently do"
    override val requiresNetwork = false

    private val TRIGGERS = listOf(
        "what can you do",
        "help",
        "what are your capabilities",
        "what do you know",
        "show me commands",
        "what commands",
        "what can i say",
        "how do i use you",
        "list your features",
        "what features"
    )

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim().lowercase()
        return if (TRIGGERS.any { t.contains(it) }) ToolInput(transcript) else null
    }

    override suspend fun execute(input: ToolInput): ToolResult =
        ToolResult.Success(capabilityProvider())
}
