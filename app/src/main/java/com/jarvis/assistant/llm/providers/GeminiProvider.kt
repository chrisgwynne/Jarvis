package com.jarvis.assistant.llm.providers

import com.jarvis.assistant.llm.LlmException
import com.jarvis.assistant.llm.LlmProvider
import com.jarvis.assistant.llm.Message
import com.jarvis.assistant.llm.NetworkClient

/**
 * Google Gemini provider.
 * Endpoint: https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
 * Key: https://aistudio.google.com/app/apikey
 * Model: gemini-2.0-flash (fast, cheap, high quality)
 *
 * WIRE FORMAT DIFFERENCES FROM OPENAI:
 *   1. API key goes in the URL query string (?key=...) NOT in a header
 *   2. "assistant" role is called "model" in Gemini
 *   3. Content is wrapped in "parts": [{"text": "..."}] not a bare string
 *   4. System prompt uses a dedicated "systemInstruction" top-level field
 *   5. Response is in candidates[0].content.parts[0].text
 *   6. Max tokens are in "generationConfig.maxOutputTokens"
 *
 * MESSAGE CONVERSION:
 *   role "system"    → systemInstruction (not in contents array)
 *   role "user"      → role "user" in contents
 *   role "assistant" → role "model" in contents
 */
class GeminiProvider(private val apiKey: String) : LlmProvider {

    override val name = "Gemini"

    private val model = "gemini-2.0-flash"

    override suspend fun complete(messages: List<Message>): String {
        if (apiKey.isBlank()) {
            throw LlmException("No API key configured for Gemini — go to Settings.")
        }

        // API key goes in the URL, not a header
        val url = "https://generativelanguage.googleapis.com/v1beta/models" +
                  "/$model:generateContent?key=$apiKey"

        // Extract system prompt
        val systemText = messages.firstOrNull { it.role == "system" }?.content ?: ""

        // Convert remaining messages: "assistant" → "model", wrap content in parts[]
        val contents = messages
            .filter { it.role != "system" }
            .map { msg ->
                GeminiContent(
                    role  = if (msg.role == "assistant") "model" else msg.role,
                    parts = listOf(GeminiPart(text = msg.content))
                )
            }

        val requestBody = NetworkClient.gson.toJson(
            GeminiRequest(
                systemInstruction = if (systemText.isNotBlank())
                    GeminiSystemInstruction(parts = listOf(GeminiPart(text = systemText)))
                    else null,
                contents          = contents,
                generationConfig  = GeminiGenConfig(maxOutputTokens = 200)
            )
        )

        val responseBody = NetworkClient.post(
            url     = url,
            headers = mapOf("Content-Type" to "application/json"),
            body    = requestBody
        )

        val parsed = NetworkClient.gson.fromJson(responseBody, GeminiResponse::class.java)
        return parsed.candidates
            ?.firstOrNull()
            ?.content
            ?.parts
            ?.firstOrNull()
            ?.text
            ?.trim()
            ?: throw LlmException("Gemini returned an empty response.")
    }

    // ── Wire-format data classes ───────────────────────────────────────────────

    private data class GeminiPart(val text: String)

    private data class GeminiContent(val role: String, val parts: List<GeminiPart>)

    private data class GeminiSystemInstruction(val parts: List<GeminiPart>)

    private data class GeminiGenConfig(val maxOutputTokens: Int)

    private data class GeminiRequest(
        val systemInstruction: GeminiSystemInstruction?,
        val contents: List<GeminiContent>,
        val generationConfig: GeminiGenConfig
    )

    private data class GeminiResponse(val candidates: List<Candidate>?) {
        data class Candidate(val content: GeminiContent?)
    }
}
