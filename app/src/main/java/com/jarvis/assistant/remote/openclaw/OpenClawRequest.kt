package com.jarvis.assistant.remote.openclaw

/**
 * OpenAI-compatible chat completions request sent to the OpenClaw /v1/chat/completions endpoint.
 * Gson serialises this correctly — no manual JSON escaping needed.
 */
data class OpenClawChatMessage(val role: String, val content: String)

data class OpenClawRequest(
    val model:      String,
    val messages:   List<OpenClawChatMessage>,
    val max_tokens: Int     = 1200,
    val stream:     Boolean = false
)
