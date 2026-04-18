package com.jarvis.assistant.llm.providers

import com.jarvis.assistant.llm.LlmException
import com.jarvis.assistant.llm.LlmProvider
import com.jarvis.assistant.llm.LlmResult
import com.jarvis.assistant.llm.Message
import com.jarvis.assistant.llm.NetworkClient
import com.jarvis.assistant.tools.framework.ToolSchema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * BaseOpenAiProvider — handles the OpenAI chat/completions wire format.
 *
 * Reused by: OpenAI, OpenRouter, Kimi (Moonshot), MiniMax.
 * Also provides [completeWithTools] for LLM function calling.
 */
abstract class BaseOpenAiProvider(
    protected val apiKey: String,
    private val baseUrl: String,
    private val model: String,
    private val maxTokens: Int = 1200
) : LlmProvider {

    protected open fun extraHeaders(): Map<String, String> = emptyMap()

    private fun buildHeaders() = buildMap<String, String> {
        put("Authorization", "Bearer $apiKey")
        put("Content-Type", "application/json")
        putAll(extraHeaders())
    }

    override suspend fun complete(messages: List<Message>): String {
        if (apiKey.isBlank()) throw LlmException("No API key configured for $name — go to Settings.")

        val requestBody = NetworkClient.gson.toJson(
            OAIRequest(
                model      = model,
                messages   = messages.map { OAIMessage(role = it.role, content = buildOaiContent(it)) },
                max_tokens = maxTokens
            )
        )
        val responseBody = NetworkClient.post("$baseUrl/chat/completions", buildHeaders(), requestBody)
        val parsed = NetworkClient.gson.fromJson(responseBody, OAIResponse::class.java)
        return parsed.choices?.firstOrNull()?.message?.content?.trim()
            ?: throw LlmException("$name returned an empty response.")
    }

    override fun streamComplete(messages: List<Message>): Flow<String> {
        if (apiKey.isBlank()) return kotlinx.coroutines.flow.flow {
            throw LlmException("No API key configured for $name — go to Settings.")
        }
        val requestBody = NetworkClient.gson.toJson(
            OAIStreamRequest(
                model      = model,
                messages   = messages.map { OAIMessage(role = it.role, content = buildOaiContent(it)) },
                max_tokens = maxTokens,
                stream     = true
            )
        )
        return NetworkClient.postStream("$baseUrl/chat/completions", buildHeaders(), requestBody)
            .map { data ->
                val chunk = NetworkClient.gson.fromJson(data, OAIStreamChunk::class.java)
                chunk.choices?.firstOrNull()?.delta?.content ?: ""
            }
            .filter { it.isNotEmpty() }
    }

    /**
     * Call the model with function-calling tools.
     * Returns [LlmResult.MultiToolCall] when the model requests multiple tools in parallel,
     * [LlmResult.ToolCall] for a single tool, or [LlmResult.Text] for a normal response.
     */
    suspend fun completeWithTools(messages: List<Message>, tools: List<ToolSchema>): LlmResult {
        if (apiKey.isBlank()) throw LlmException("No API key configured for $name — go to Settings.")

        val oaiTools = tools.map { schema ->
            OAITool(
                type     = "function",
                function = OAIToolFunction(
                    name        = schema.name,
                    description = schema.description,
                    parameters  = schema.parameters
                )
            )
        }

        val requestBody = NetworkClient.gson.toJson(
            OAIToolRequest(
                model       = model,
                messages    = messages.map { OAIMessage(role = it.role, content = buildOaiContent(it)) },
                max_tokens  = maxTokens,
                tools       = oaiTools,
                tool_choice = "auto"
            )
        )

        val responseBody = NetworkClient.post("$baseUrl/chat/completions", buildHeaders(), requestBody)
        val parsed       = NetworkClient.gson.fromJson(responseBody, OAIToolResponse::class.java)
        val choice       = parsed.choices?.firstOrNull()

        val toolCalls = choice?.message?.tool_calls
        return when {
            toolCalls != null && toolCalls.size > 1 -> {
                LlmResult.MultiToolCall(toolCalls.map { tc ->
                    LlmResult.ToolCall(toolName = tc.function.name, argsJson = tc.function.arguments)
                })
            }
            toolCalls?.firstOrNull() != null -> {
                val tc = toolCalls.first()
                LlmResult.ToolCall(toolName = tc.function.name, argsJson = tc.function.arguments)
            }
            else -> LlmResult.Text(choice?.message?.content?.trim() ?: "")
        }
    }

    // ── Image content builder ─────────────────────────────────────────────────

    /**
     * Build OpenAI content: if the message carries an image, return a list of
     * content blocks (image_url + text); otherwise return the plain text string.
     * Gson serialises Any? correctly — lists become JSON arrays, strings stay strings.
     */
    private fun buildOaiContent(msg: Message): Any? = if (msg.imageBase64 != null) {
        listOf(
            mapOf("type" to "image_url",
                  "image_url" to mapOf("url" to "data:image/jpeg;base64,${msg.imageBase64}")),
            mapOf("type" to "text", "text" to msg.content)
        )
    } else {
        msg.content
    }

    // ── Wire-format data classes ──────────────────────────────────────────────

    private data class OAIMessage(val role: String, val content: Any?)

    private data class OAIRequest(val model: String, val messages: List<OAIMessage>, val max_tokens: Int)
    private data class OAIStreamRequest(val model: String, val messages: List<OAIMessage>, val max_tokens: Int, val stream: Boolean)

    private data class OAIResponse(val choices: List<Choice>?) {
        data class Choice(val message: Msg?)
        data class Msg(val content: String?)
    }

    private data class OAIStreamChunk(val choices: List<StreamChoice>?) {
        data class StreamChoice(val delta: Delta?)
        data class Delta(val content: String?)
    }

    // Function calling wire-format
    private data class OAITool(val type: String, val function: OAIToolFunction)
    private data class OAIToolFunction(val name: String, val description: String, val parameters: Map<String, Any>)

    private data class OAIToolRequest(
        val model: String,
        val messages: List<OAIMessage>,
        val max_tokens: Int,
        val tools: List<OAITool>,
        val tool_choice: String
    )

    private data class OAIToolResponse(val choices: List<ToolChoice>?) {
        data class ToolChoice(val message: ToolMsg?)
        data class ToolMsg(val content: String?, val tool_calls: List<ToolCallEntry>?)
        data class ToolCallEntry(val function: ToolCallFunction)
        data class ToolCallFunction(val name: String, val arguments: String)
    }
}
