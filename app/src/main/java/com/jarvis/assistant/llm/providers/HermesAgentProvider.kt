package com.jarvis.assistant.llm.providers

/**
 * HermesAgentProvider — talks to a self-hosted Hermes Agent
 * (https://github.com/NousResearch/hermes-agent).
 *
 * Hermes exposes an OpenAI-compatible /v1/chat/completions endpoint with
 * Bearer-token auth (the API_SERVER_KEY when bound to a non-loopback
 * address).  That makes the integration a thin extension of
 * [BaseOpenAiProvider] — no new wire format, no SSE divergence.
 *
 * Configuration:
 *   • baseUrl — typically `http(s)://<host>:<port>/v1` for a LAN /
 *               Tailscale deployment.  When the user enters
 *               `http://hermes.lan:8000` we append `/v1` automatically
 *               via [normaliseBaseUrl].
 *   • apiKey  — the API_SERVER_KEY value the Hermes operator configured.
 *               Required (non-blank) here even for loopback/trusted-LAN
 *               deployments — accidentally sending unauthenticated traffic
 *               to a public Hermes is the failure mode worth blocking at
 *               the client.  Set any string when Hermes is configured to
 *               accept unauthenticated requests on loopback.
 *   • model   — the advertised profile name, defaults to "hermes-agent".
 *
 * Streaming + tool calling come for free from BaseOpenAiProvider; Hermes
 * also emits its own `hermes.tool.progress` events alongside the normal
 * `chat.completion.chunk` stream — those are silently ignored by the
 * standard OpenAI parser, which is the desired behaviour for the assistant
 * surface (we only need the assistant text to feed TTS).
 */
class HermesAgentProvider(
    apiKey: String,
    rawBaseUrl: String,
    model: String = "hermes-agent",
    maxTokens: Int = 1200,
) : BaseOpenAiProvider(
    apiKey    = apiKey,
    baseUrl   = normaliseBaseUrl(rawBaseUrl),
    model     = model,
    maxTokens = maxTokens,
) {
    override val name: String = "Hermes"

    companion object {
        /**
         * Accept whichever form the user typed in Settings:
         *   http://host:port            → http://host:port/v1
         *   http://host:port/v1         → http://host:port/v1
         *   http://host:port/v1/        → http://host:port/v1
         *   https://host/api/v1         → https://host/api/v1   (left alone)
         */
        internal fun normaliseBaseUrl(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.isEmpty()) return trimmed
            // If the URL already ends with /vN (one or two digits) or contains
            // /api/v leave it; otherwise append /v1.
            val tail = trimmed.substringAfterLast('/')
            val versioned = tail.matches(Regex("v\\d{1,2}"))
            return if (versioned) trimmed else "$trimmed/v1"
        }
    }
}
