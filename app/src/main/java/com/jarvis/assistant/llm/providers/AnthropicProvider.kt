package com.jarvis.assistant.llm.providers

import com.jarvis.assistant.llm.LlmException
import com.jarvis.assistant.llm.LlmProvider
import com.jarvis.assistant.llm.Message
import com.jarvis.assistant.llm.NetworkClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Anthropic (Claude) provider.
 * Endpoint: https://api.anthropic.com/v1/messages
 * Key: https://console.anthropic.com/settings/keys
 *
 * DIFFERENCES FROM OPENAI FORMAT:
 *   1. Auth header is "x-api-key" not "Authorization: Bearer"
 *   2. Required header "anthropic-version: 2023-06-01"
 *   3. System prompt is a TOP-LEVEL "system" field, NOT a message with role "system".
 *      Anthropic rejects messages with role "system" in the array.
 *   4. Response is in "content[0].text" not "choices[0].message.content"
 *   5. "max_tokens" is REQUIRED (not optional like OpenAI)
 *   6. Messages must strictly alternate user/assistant (no two consecutive same role)
 */
class AnthropicProvider(private val apiKey: String) : LlmProvider {

    override val name = "Anthropic"

    override suspend fun complete(messages: List<Message>): String {
        if (apiKey.isBlank()) {
            throw LlmException("No API key configured for Anthropic — go to Settings.")
        }

        // Separate system prompt from conversation messages
        val systemText = messages.firstOrNull { it.role == "system" }?.content ?: ""
        val chatMessages = messages
            .filter { it.role != "system" }
            .map { AnthropicMessage(role = it.role, content = it.content) }

        val requestBody = NetworkClient.gson.toJson(
            AnthropicRequest(
                model      = "claude-haiku-4-5-20251001",
                max_tokens = 400,
                system     = systemText,
                messages   = chatMessages
            )
        )

        val responseBody = NetworkClient.post(
            url = "https://api.anthropic.com/v1/messages",
            headers = mapOf(
                "x-api-key"         to apiKey,
                "anthropic-version" to "2023-06-01",
                "Content-Type"      to "application/json"
            ),
            body = requestBody
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
            .map { AnthropicMessage(role = it.role, content = it.content) }

        val requestBody = NetworkClient.gson.toJson(
            AnthropicStreamRequest(
                model      = "claude-haiku-4-5-20251001",
                max_tokens = 400,
                system     = systemText,
                messages   = chatMessages,
                stream     = true
            )
        )

        // Anthropic SSE events look like:
        //   data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Hello"}}
        return NetworkClient.postStream(
            url     = "https://api.anthropic.com/v1/messages",
            headers = mapOf(
                "x-api-key"         to apiKey,
                "anthropic-version" to "2023-06-01",
                "Content-Type"      to "application/json"
            ),
            body = requestBody
        )
            .map { data ->
                val event = NetworkClient.gson.fromJson(data, AnthropicStreamEvent::class.java)
                if (event.type == "content_block_delta") event.delta?.text ?: "" else ""
            }
            .filter { it.isNotEmpty() }
    }

    // ── Wire-format data classes ───────────────────────────────────────────────

    private data class AnthropicMessage(val role: String, val content: String)

    private data class AnthropicRequest(
        val model: String,
        val max_tokens: Int,
        val system: String,
        val messages: List<AnthropicMessage>
    )

    private data class AnthropicResponse(val content: List<ContentBlock>?) {
        data class ContentBlock(val type: String?, val text: String?)
    }

    // Streaming wire-format
    private data class AnthropicStreamRequest(
        val model: String,
        val max_tokens: Int,
        val system: String,
        val messages: List<AnthropicMessage>,
        val stream: Boolean
    )

    private data class AnthropicStreamEvent(
        val type: String?,
        val delta: AnthropicDelta?
    ) {
        data class AnthropicDelta(val type: String?, val text: String?)
    }
}
