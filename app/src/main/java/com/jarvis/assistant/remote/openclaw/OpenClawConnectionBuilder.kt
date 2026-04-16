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

    /** HTTP health endpoint: http(s)://host:port/health */
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
