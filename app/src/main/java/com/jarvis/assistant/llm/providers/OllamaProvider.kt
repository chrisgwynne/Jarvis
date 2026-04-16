package com.jarvis.assistant.llm.providers

import com.jarvis.assistant.llm.LlmException
import com.jarvis.assistant.llm.LlmProvider
import com.jarvis.assistant.llm.Message
import com.jarvis.assistant.llm.NetworkClient

/**
 * Ollama provider — runs a local LLM on your home server or PC.
 * No API key required; just the base URL of your Ollama instance.
 * Default base URL: http://192.168.1.1:11434 (configurable in Settings)
 *
 * SETUP:
 *   1. Install Ollama: https://ollama.ai
 *   2. Pull a model: ollama pull llama3.2
 *   3. By default Ollama only listens on 127.0.0.1. To allow LAN access:
 *      Set OLLAMA_HOST=0.0.0.0 before starting, or on Linux/Mac:
 *        launchctl setenv OLLAMA_HOST "0.0.0.0"
 *   4. Find your server's LAN IP and enter it in Settings → Ollama Base URL.
 *      Example: http://192.168.1.42:11434
 *
 * MODEL:
 *   Hardcoded to "llama3.2" — a good default for voice assistant use.
 *   Future session: add a model picker in Settings.
 *
 * WIRE FORMAT:
 *   POST {baseUrl}/api/chat
 *   { "model": "llama3.2", "messages": [...], "stream": false }
 *   Response: { "message": { "content": "..." } }
 */
class OllamaProvider(private val baseUrl: String) : LlmProvider {

    override val name = "Ollama"

    override suspend fun complete(messages: List<Message>): String {
        if (baseUrl.isBlank()) {
            throw LlmException("No Ollama base URL configured — go to Settings.")
        }

        // Normalise URL — strip trailing slash so we can safely append the path
        val cleanBase = baseUrl.trimEnd('/')
        val url = "$cleanBase/api/chat"

        val requestBody = NetworkClient.gson.toJson(
            OllamaRequest(
                model    = "llama3.2",
                messages = messages.map { OllamaMessage(role = it.role, content = it.content) },
                stream   = false
            )
        )

        val responseBody = NetworkClient.post(
            url = url,
            headers = mapOf("Content-Type" to "application/json"),
            body = requestBody
        )

        val parsed = NetworkClient.gson.fromJson(responseBody, OllamaResponse::class.java)
        return parsed.message?.content?.trim()
            ?: throw LlmException("Ollama returned an empty response.")
    }

    // ── Wire-format data classes ───────────────────────────────────────────────

    private data class OllamaMessage(val role: String, val content: String)

    private data class OllamaRequest(
        val model: String,
        val messages: List<OllamaMessage>,
        val stream: Boolean
    )

    private data class OllamaResponse(val message: OllamaMsg?) {
        data class OllamaMsg(val content: String?)
    }
}
