package com.jarvis.assistant.tools.device

import com.jarvis.assistant.memory.MemoryRetriever
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * MemoryRecallTool — answers questions about past conversations.
 *
 * Matches natural queries like:
 *   "what did I ask earlier?"
 *   "do you remember what I said yesterday?"
 *   "what have we talked about?"
 *
 * Returns ToolResult.Augmented so the LLM can synthesise a natural reply
 * using the retrieved memories as hidden context.
 */
class MemoryRecallTool(private val memoryRetriever: MemoryRetriever) : Tool {

    override val name        = "memory_recall"
    override val description = "Retrieves past conversation summaries and facts to answer recall questions"

    override fun schema() = ToolSchema(
        name        = name,
        description = "Search past conversations and stored memories for context relevant to what the user just said. Use when they ask about something previously discussed.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf("type" to "string", "description" to "What to search memory for; usually the user's question verbatim")
            ),
            "required" to emptyList<String>()
        )
    )

    private val PATTERNS = listOf(
        Regex("what did (i|we) (ask|say|talk(ed)? about|discuss(ed)?)", RegexOption.IGNORE_CASE),
        Regex("do you remember",                                         RegexOption.IGNORE_CASE),
        Regex("what (was|were) (i|we) (saying|talking|asking|discussing)", RegexOption.IGNORE_CASE),
        Regex("(recall|remember) (what|when|that)",                      RegexOption.IGNORE_CASE),
        Regex("what have (i|we) (asked|said|talked)",                    RegexOption.IGNORE_CASE),
        Regex("what did (jarvis|you) (tell|say) (me|us)",                RegexOption.IGNORE_CASE),
        Regex("(earlier today|last time|yesterday|before)",              RegexOption.IGNORE_CASE),
        Regex("any (previous|past|earlier) conversations?",              RegexOption.IGNORE_CASE),
    )

    override fun matches(transcript: String): ToolInput? =
        if (PATTERNS.any { it.containsMatchIn(transcript) }) ToolInput(transcript) else null

    override suspend fun execute(input: ToolInput): ToolResult {
        val memories = memoryRetriever.retrieveRelevant(input.transcript, limit = 5)

        if (memories.isEmpty()) {
            return ToolResult.Success(
                spokenFeedback = "I don't have any relevant memories from our previous conversations."
            )
        }

        // Build augmented context for the LLM to synthesise from
        val ctx = memories.joinToString("\n") { "- ${it.content}" }
        val augmented = """
            The user asked: "${input.transcript}"

            These are relevant notes from previous conversations:
            $ctx

            Using the context above, answer the user's question naturally and concisely in 1-3 sentences.
        """.trimIndent()

        return ToolResult.Augmented(augmentedTranscript = augmented)
    }
}
