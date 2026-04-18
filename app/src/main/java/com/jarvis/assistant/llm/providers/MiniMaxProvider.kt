package com.jarvis.assistant.llm.providers

/**
 * MiniMax provider — MiniMax AI's chat model.
 * Uses the OpenAI-compatible endpoint.
 * Endpoint: https://api.minimax.chat/v1/chat/completions
 * Key: https://platform.minimaxi.com/user-center/basic-information/interface-key
 *
 * Model: MiniMax-Text-01 (flagship), abab6.5s-chat (lighter/faster)
 *
 * Note: MiniMax has two API regions:
 *   China:         api.minimax.chat   (default)
 *   International: api.minimaxi.chat  (use this if you signed up at platform.minimaxi.com)
 * The base URL is now configurable in Settings → LLM Provider.
 */
class MiniMaxProvider(apiKey: String, baseUrl: String, model: String, maxTokens: Int = 1200) : BaseOpenAiProvider(
    apiKey    = apiKey,
    baseUrl   = baseUrl,
    model     = model,
    maxTokens = maxTokens
) {
    override val name = "MiniMax"
}
