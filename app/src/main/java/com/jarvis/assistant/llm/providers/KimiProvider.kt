package com.jarvis.assistant.llm.providers

/**
 * Kimi provider — Moonshot AI's long-context model.
 * Uses the OpenAI-compatible chat/completions endpoint.
 * Endpoint: https://api.moonshot.cn/v1/chat/completions
 * Key: https://platform.moonshot.cn/console/api-keys
 *
 * Models: moonshot-v1-8k (default) / moonshot-v1-32k / moonshot-v1-128k
 * The 8k model is sufficient for voice assistant interactions.
 */
class KimiProvider(apiKey: String) : BaseOpenAiProvider(
    apiKey  = apiKey,
    baseUrl = "https://api.moonshot.cn/v1",
    model   = "moonshot-v1-8k"
) {
    override val name = "Kimi"
}
