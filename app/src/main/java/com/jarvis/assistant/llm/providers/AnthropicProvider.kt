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
 * Anthropic (Claude) provider with prompt caching.
 *
 * The system prompt is serialized as a content-block array with
 * `cache_control: {type: "ephemeral"}` on the last block.  This instructs
 * Anthropic's API to cache the system prompt for up to 5 minutes, cutting
 * input costs by ~90% and reducing time-to-first-token by ~50% on repeat calls.
 *
 * The `anthropic-beta: prompt-caching-2024-07-31` header is required.
 */
class AnthropicProvider(private val apiKey: String, private val maxTokens: Int = 1200) : LlmProvider {

    override val name = "Anthropic"

    private val headers = mapOf(
        "x-api-key"         to apiKey,
        "anthropic-version" to "2023-06-01",
        "anthropic-beta"    to "prompt-caching-2024-07-31",
        "Content-Type"      to "application/json"
    )

    override suspend fun complete(messages: List<Message>): String {
        if (apiKey.isBlank()) {
            throw LlmException("No API key configured for Anthropic — go to Settings.")
        }

        val systemText = messages.firstOrNull { it.role == "system" }?.content ?: ""
        val chatMessages = messages
            .filter { it.role != "system" }
            .map { AnthropicMessage(role = it.role, content = buildAnthropicContent(it)) }

        val requestBody = NetworkClient.gson.toJson(
            AnthropicRequest(
                model      = "claude-haiku-4-5-20251001",
                max_tokens = maxTokens,
                system     = buildSystemBlocks(systemText),
                messages   = chatMessages
            )
        )

        val responseBody = NetworkClient.post(
            url     = "https://api.anthropic.com/v1/messages",
            headers = headers,
            body    = requestBody
        )

        val parsed = NetworkClient.gson.fromJson(responseBody, AnthropicResponse::class.java)
        return parsed.content
            ?.firstOrNull { it.type == "text" }
            ?.text
            ?.trim()
            ?: throw LlmException("Anthropic returned an empty response.")
    }

    override fun streamComplete(messages: List<Message>): Flow<String> {
        if (apiKey.isBlank()) {
            return kotlinx.coroutines.flow.flow {
                throw LlmException("No API key configured for Anthropic — go to Settings.")
            }
        }

        val systemText = messages.firstOrNull { it.role == "system" }?.content ?: ""
        val chatMessages = messages
            .filter { it.role != "system" }
            .map { AnthropicMessage(role = it.role, content = buildAnthropicContent(it)) }

        val requestBody = NetworkClient.gson.toJson(
            AnthropicStreamRequest(
                model      = "claude-haiku-4-5-20251001",
                max_tokens = maxTokens,
                system     = buildSystemBlocks(systemText),
                messages   = chatMessages,
                stream     = true
            )
        )

        return NetworkClient.postStream(
            url     = "https://api.anthropic.com/v1/messages",
            headers = headers,
            body    = requestBody
        )
            .map { data ->
                val event = NetworkClient.gson.fromJson(data, AnthropicStreamEvent::class.java)
                if (event.type == "content_block_delta") event.delta?.text ?: "" else ""
            }
            .filter { it.isNotEmpty() }
    }

    /**
     * Call Claude with tool schemas — returns [LlmResult.ToolCall] or [LlmResult.Text].
     */
    suspend fun completeWithTools(messages: List<Message>, tools: List<ToolSchema>): LlmResult {
        if (apiKey.isBlank()) throw LlmException("No API key configured for Anthropic — go to Settings.")

        val systemText   = messages.firstOrNull { it.role == "system" }?.content ?: ""
        val chatMessages = messages.filter { it.role != "system" }
            .map { AnthropicMessage(role = it.role, content = buildAnthropicContent(it)) }

        val anthropicTools = tools.map { schema ->
            AnthropicTool(
                name         = schema.name,
                description  = schema.description,
                input_schema = schema.parameters
            )
        }

        val requestBody = NetworkClient.gson.toJson(
            AnthropicToolRequest(
                model      = "claude-haiku-4-5-20251001",
                max_tokens = maxTokens,
                system     = buildSystemBlocks(systemText),
                messages   = chatMessages,
                tools      = anthropicTools
            )
        )

        val responseBody = NetworkClient.post(
            url     = "https://api.anthropic.com/v1/messages",
            headers = headers,
            body    = requestBody
        )

        val parsed = NetworkClient.gson.fromJson(responseBody, AnthropicToolResponse::class.java)
        val toolBlocks = parsed.content
            ?.filter { it.type == "tool_use" && !it.name.isNullOrBlank() }
            ?: emptyList()
        return when {
            toolBlocks.size > 1 -> {
                LlmResult.MultiToolCall(toolBlocks.map { tb ->
                    val argsJson = NetworkClient.gson.toJson(tb.input ?: emptyMap<String, Any>())
                    LlmResult.ToolCall(toolName = tb.name!!, argsJson = argsJson)
                })
            }
            toolBlocks.size == 1 -> {
                val tb = toolBlocks.first()
                val argsJson = NetworkClient.gson.toJson(tb.input ?: emptyMap<String, Any>())
                LlmResult.ToolCall(toolName = tb.name!!, argsJson = argsJson)
            }
            else -> {
                val text = parsed.content?.firstOrNull { it.type == "text" }?.text?.trim() ?: ""
                LlmResult.Text(text)
            }
        }
    }

    // ── Image content builder ─────────────────────────────────────────────────

    /**
     * Build Anthropic content: if the message carries an image, return a list of
     * content blocks (image + text); otherwise return the plain text string.
     */
    private fun buildAnthropicContent(msg: Message): Any = if (msg.imageBase64 != null) {
        listOf(
            mapOf("type" to "image",
                  "source" to mapOf("type" to "base64", "media_type" to "image/jpeg",
                                    "data" to msg.imageBase64)),
            mapOf("type" to "text", "text" to msg.content)
        )
    } else {
        msg.content
    }

    /**
     * Serialize the system prompt as a single cacheable content block.
     * Anthropic requires the `system` field to be a list of blocks (not a plain
     * string) when using the prompt-caching beta.
     */
    private fun buildSystemBlocks(systemText: String): List<SystemBlock> {
        if (systemText.isBlank()) return emptyList()
        return listOf(
            SystemBlock(
                type         = "text",
                text         = systemText,
                cache_control = CacheControl(type = "ephemeral")
            )
        )
    }

    // ── Wire-format data classes ───────────────────────────────────────────────

    private data class AnthropicMessage(val role: String, val content: Any)
    private data class CacheControl(val type: String)
    private data class SystemBlock(val type: String, val text: String, val cache_control: CacheControl)

    private data class AnthropicRequest(
        val model: String,
        val max_tokens: Int,
        val system: List<SystemBlock>,
        val messages: List<AnthropicMessage>
    )

    private data class AnthropicResponse(val content: List<ContentBlock>?) {
        data class ContentBlock(val type: String?, val text: String?)
    }

    private data class AnthropicStreamRequest(
        val model: String,
        val max_tokens: Int,
        val system: List<SystemBlock>,
        val messages: List<AnthropicMessage>,
        val stream: Boolean
    )

    private data class AnthropicStreamEvent(
        val type: String?,
        val delta: AnthropicDelta?
    ) {
        data class AnthropicDelta(val type: String?, val text: String?)
    }

    // Tool calling wire-format
    private data class AnthropicTool(
        val name: String,
        val description: String,
        val input_schema: Map<String, Any>
    )

    private data class AnthropicToolRequest(
        val model: String,
        val max_tokens: Int,
        val system: List<SystemBlock>,
        val messages: List<AnthropicMessage>,
        val tools: List<AnthropicTool>
    )

    private data class AnthropicToolResponse(val content: List<ToolContentBlock>?) {
        data class ToolContentBlock(
            val type: String?,
            val text: String?,
            val name: String?,
            val input: Map<String, Any>?
        )
    }
}
