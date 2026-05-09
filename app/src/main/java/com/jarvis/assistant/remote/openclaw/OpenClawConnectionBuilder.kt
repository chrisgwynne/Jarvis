package com.jarvis.assistant.remote.openclaw

/**
 * Builds connection URLs from an [OpenClawSettings] snapshot.
 * Pure functions — no I/O, safe to call from any thread.
 */
object OpenClawConnectionBuilder {

    /** WebSocket endpoint: ws(s)://host:port/gateway */
    fun buildWsEndpoint(settings: OpenClawSettings): String {
        val scheme = if (settings.secure) "wss" else "ws"
        val host   = settings.host.trim().trimEnd('/')
        return "$scheme://$host:${settings.port}/gateway"
    }

    /**
     * OpenAI-compatible chat completions endpoint.
     * Uses [OpenClawSettings.llmBaseUrl] if set, so you can route LLM queries to
     * a different port/service (e.g. Hermes on :8642) while keeping the node
     * WebSocket on the primary host:port.
     */
    fun buildChatEndpoint(settings: OpenClawSettings): String {
        val base = settings.llmBaseUrl.trim().trimEnd('/').ifBlank {
            val scheme = if (settings.secure) "https" else "http"
            "$scheme://${settings.host.trim().trimEnd('/')}:${settings.port}"
        }
        return "$base/v1/chat/completions"
    }

    /** OpenAI-compatible models endpoint: http(s)://host:port/v1/models (used for health checks) */
    fun buildModelsEndpoint(settings: OpenClawSettings): String {
        val scheme = if (settings.secure) "https" else "http"
        val host   = settings.host.trim().trimEnd('/')
        return "$scheme://$host:${settings.port}/v1/models"
    }

    /** HTTP health endpoint: http(s)://host:port/health (kept for backward compat) */
    fun buildHealthEndpoint(settings: OpenClawSettings): String {
        val scheme = if (settings.secure) "https" else "http"
        val host   = settings.host.trim().trimEnd('/')
        return "$scheme://$host:${settings.port}/health"
    }

    /**
     * Lightweight host validation.
     * Rejects blank, whitespace-containing, and protocol-prefixed strings.
     * Callers should show an error message when this returns false.
     */
    fun validateHost(host: String): Boolean =
        host.isNotBlank() && !host.contains(' ') && !host.contains("://")
}
