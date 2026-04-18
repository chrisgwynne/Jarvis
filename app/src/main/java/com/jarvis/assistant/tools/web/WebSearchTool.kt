package com.jarvis.assistant.tools.web

import com.jarvis.assistant.tools.WebSearch
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema
import com.jarvis.assistant.util.SettingsStore

class WebSearchTool(
    private val webSearch: WebSearch,
    private val settings: SettingsStore
) : Tool {

    override val name = "web_search"
    override val description = "Search the web and inject results as context for the LLM"
    override val requiresNetwork = true

    override fun schema() = ToolSchema(
        name        = name,
        description = "Search the internet for current information: news, prices, live events, people, places.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf("type" to "string", "description" to "The search query")
            ),
            "required" to listOf("query")
        )
    )

    // Specific live-data keywords that reliably indicate a search is needed.
    // Deliberately narrow: temporal words (today/tomorrow) and vague terms (price,
    // match, result, who is, what is) are excluded — they match too many personal
    // statements and casual questions that the LLM can answer without a search.
    private val TRIGGERS = setOf(
        "news", "latest news", "breaking news", "headlines",
        "bitcoin", "ethereum", "crypto", "stock price", "share price",
        "search for", "look up", "look this up", "search online",
        "find out ", "google ",
        "nearby", "near me", "directions to",
        "who won the", "tell me about "
    )

    override fun matches(transcript: String): ToolInput? {
        val lower = transcript.lowercase()
        return if (TRIGGERS.any { lower.contains(it) }) ToolInput(transcript) else null
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val ctx = webSearch.search(input.transcript, settings.braveSearchApiKey)
        return if (ctx.isNotBlank()) {
            ToolResult.Augmented("$ctx\n\nUser asked: ${input.transcript}")
        } else {
            ToolResult.Failure("Hmm, nothing useful came back. Want me to try another angle?")
        }
    }
}
