package com.jarvis.assistant.llm.providers

import com.jarvis.assistant.llm.LlmException
import com.jarvis.assistant.llm.LlmProvider
import com.jarvis.assistant.llm.LlmResult
import com.jarvis.assistant.llm.Message
import com.jarvis.assistant.llm.NetworkClient
import com.jarvis.assistant.tools.framework.ToolSchema

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

    /**
     * Call Ollama with tool declarations — supported on llama3.1+, mistral, and other
     * models that implement OpenAI-compatible function calling.
     *
     * Note: Ollama returns `arguments` as a JSON *object* (not a string like OpenAI),
     * so we re-serialize it to a string for uniform downstream handling.
     */
    suspend fun completeWithTools(messages: List<Message>, tools: List<ToolSchema>): LlmResult {
        if (baseUrl.isBlank()) throw LlmException("No Ollama base URL configured — go to Settings.")
        val cleanBase = baseUrl.trimEnd('/')

        val ollamaTools = tools.map { schema ->
            OllamaTool(
                type     = "function",
                function = OllamaToolFunction(
                    name        = schema.name,
                    description = schema.description,
                    parameters  = schema.parameters
                )
            )
        }

        val requestBody = NetworkClient.gson.toJson(
            OllamaToolRequest(
                model    = "llama3.2",
                messages = messages.map { OllamaMessage(role = it.role, content = it.content) },
                tools    = ollamaTools,
                stream   = false
            )
        )

        val responseBody = NetworkClient.post(
            url     = "$cleanBase/api/chat",
            headers = mapOf("Content-Type" to "application/json"),
            body    = requestBody
        )

        val parsed   = NetworkClient.gson.fromJson(responseBody, OllamaToolResponse::class.java)
        val toolCall = parsed.message?.tool_calls?.firstOrNull()

        return if (toolCall != null) {
            // Re-serialize the object-typed arguments to a JSON string
            val argsJson = NetworkClient.gson.toJson(toolCall.function.arguments ?: emptyMap<String, Any>())
            LlmResult.ToolCall(toolName = toolCall.function.name ?: "", argsJson = argsJson)
        } else {
            LlmResult.Text(parsed.message?.content?.trim() ?: "")
        }
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

    // Function calling wire-format
    private data class OllamaToolFunction(
        val name: String,
        val description: String,
        val parameters: Map<String, Any>
    )
    private data class OllamaTool(val type: String, val function: OllamaToolFunction)
    private data class OllamaToolRequest(
        val model: String,
        val messages: List<OllamaMessage>,
        val tools: List<OllamaTool>,
        val stream: Boolean
    )
    private data class OllamaToolResponse(val message: OllamaToolMsg?) {
        data class OllamaToolMsg(val content: String?, val tool_calls: List<OllamaToolCall>?)
        data class OllamaToolCall(val function: OllamaToolCallFunction)
        data class OllamaToolCallFunction(val name: String?, val arguments: Map<String, Any>?)
    }
}
