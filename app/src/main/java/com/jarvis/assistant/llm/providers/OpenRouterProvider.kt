package com.jarvis.assistant.llm.providers

/**
 * OpenRouter provider — routes to any model via a single API key.
 * Default model: openai/gpt-4o-mini (cheap and fast).
 * Endpoint: https://openrouter.ai/api/v1/chat/completions
 * Key: https://openrouter.ai/keys
 *
 * OpenRouter requires an HTTP-Referer header identifying your app.
 * We also send X-Title so it shows up nicely in the OpenRouter dashboard.
 */
class OpenRouterProvider(apiKey: String) : BaseOpenAiProvider(
    apiKey  = apiKey,
    baseUrl = "https://openrouter.ai/api/v1",
    model   = "openai/gpt-4o-mini"
) {
    override val name = "OpenRouter"

    override fun extraHeaders() = mapOf(
        "HTTP-Referer" to "https://github.com/jarvis-android",
        "X-Title"      to "Jarvis Voice Assistant"
    )
}
