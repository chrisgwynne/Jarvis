package com.jarvis.assistant.llm.providers

/**
 * OpenAI provider — GPT-4o-mini by default (cheap, fast, excellent quality).
 * Endpoint: https://api.openai.com/v1/chat/completions
 * Key: https://platform.openai.com/api-keys
 */
class OpenAiProvider(apiKey: String, maxTokens: Int = 1200) : BaseOpenAiProvider(
    apiKey     = apiKey,
    baseUrl    = "https://api.openai.com/v1",
    model      = "gpt-4o-mini",
    maxTokens  = maxTokens
) {
    override val name = "OpenAI"
}
