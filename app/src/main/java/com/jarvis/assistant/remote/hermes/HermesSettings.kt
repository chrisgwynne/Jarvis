package com.jarvis.assistant.remote.hermes

/**
 * Immutable view of the user's Hermes Agent connection settings.
 *
 * Constructed from [SettingsStore] so the encrypted on-disk representation
 * is the single source of truth; this data class only exists to pass the
 * config around the call sites without leaking the SettingsStore reference
 * into the network client.
 */
data class HermesSettings(
    val enabled:   Boolean,
    val host:      String,
    val port:      Int,
    val secure:    Boolean,
    val apiKey:    String,
    val profile:   String,
    val timeoutMs: Long,
) {
    /** True when host + apiKey are set well enough to attempt a request. */
    val isUsable: Boolean
        get() = enabled && host.isNotBlank() && port in 1..65535

    /**
     * `http://host:port` or `https://host:port` — no trailing slash.
     * Used as the base for [/api/jobs](HermesJobsClient) and as the seed
     * for [HermesAgentProvider.normaliseBaseUrl].
     */
    fun baseOrigin(): String = (if (secure) "https://" else "http://") + "$host:$port"

    /** Convenience: the OpenAI-compatible base for the LLM provider. */
    fun llmBaseUrl(): String = "${baseOrigin()}/v1"
}
