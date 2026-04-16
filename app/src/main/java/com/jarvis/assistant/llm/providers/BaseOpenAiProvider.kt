package com.jarvis.assistant.llm.providers

import com.jarvis.assistant.llm.LlmException
import com.jarvis.assistant.llm.LlmProvider
import com.jarvis.assistant.llm.Message
import com.jarvis.assistant.llm.NetworkClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * BaseOpenAiProvider — handles the OpenAI chat/completions wire format.
 *
 * Reused by: OpenAI, OpenRouter, Kimi (Moonshot), MiniMax — they all accept
 * identical JSON bodies; only the base URL, model name, and optional extra
 * headers differ.
 *
 * REQUEST:
 *   POST {baseUrl}/chat/completions
 *   { "model": "...", "messages": [...], "max_tokens": 200 }
 *
 * RESPONSE:
 *   { "choices": [{ "message": { "content": "..." } }] }
 */
abstract class BaseOpenAiProvider(
    protected val apiKey: String,
    private val baseUrl: String,     // e.g. "https://api.openai.com/v1"
    private val model: String        // e.g. "gpt-4o-mini"
) : LlmProvider {

    /** Subclasses can add provider-specific headers (e.g. OpenRouter needs HTTP-Referer). */
    protected open fun extraHeaders(): Map<String, String> = emptyMap()

    override suspend fun complete(messages: List<Message>): String {
        if (apiKey.isBlank()) {
            throw LlmException("No API key configured for $name — go to Settings.")
        }

        val url = "$baseUrl/chat/completions"

        val headers = buildMap {
            put("Authorization", "Bearer $apiKey")
            put("Content-Type", "application/json")
            putAll(extraHeaders())
        }

        // Build the request body using Gson so we never produce malformed JSON
        val requestBody = NetworkClient.gson.toJson(
            OAIRequest(
                model    = model,
                messages = messages.map { OAIMessage(role = it.role, content = it.content) },
                max_tokens = 400
            )
        )

        val responseBody = NetworkClient.post(url, headers, requestBody)

        // Parse choices[0].message.content
        val parsed = NetworkClient.gson.fromJson(responseBody, OAIResponse::class.java)
        return parsed.choices
            ?.firstOrNull()
            ?.message
            ?.content
            ?.trim()
            ?: throw LlmException("$name returned an empty response.")
    }

    override fun streamComplete(messages: List<Message>): Flow<String> {
        if (apiKey.isBlank()) {
            return kotlinx.coroutines.flow.flow {
                throw LlmException("No API key configured for $name — go to Settings.")
            }
        }

        val url = "$baseUrl/chat/completions"
        val headers = buildMap {
            put("Authorization", "Bearer $apiKey")
            put("Content-Type", "application/json")
            putAll(extraHeaders())
        }
        val requestBody = NetworkClient.gson.toJson(
            OAIStreamRequest(
                model      = model,
                messages   = messages.map { OAIMessage(role = it.role, content = it.content) },
                max_tokens = 400,
                stream     = true
            )
        )

        return NetworkClient.postStream(url, headers, requestBody)
            .map { data ->
                val chunk = NetworkClient.gson.fromJson(data, OAIStreamChunk::class.java)
                chunk.choices?.firstOrNull()?.delta?.content ?: ""
            }
            .filter { it.isNotEmpty() }
    }

    // ── Wire-format data classes (private — outside world sees only Message) ──

    private data class OAIMessage(val role: String, val content: String)

    private data class OAIRequest(
        val model: String,
        val messages: List<OAIMessage>,
        val max_tokens: Int
    )

    private data class OAIResponse(val choices: List<Choice>?) {
        data class Choice(val message: Msg?)
        data class Msg(val content: String?)
    }

    // Streaming wire-format
    private data class OAIStreamRequest(
        val model: String,
        val messages: List<OAIMessage>,
        val max_tokens: Int,
        val stream: Boolean
    )

    private data class OAIStreamChunk(val choices: List<StreamChoice>?) {
        data class StreamChoice(val delta: Delta?)
        data class Delta(val content: String?)
    }
}
