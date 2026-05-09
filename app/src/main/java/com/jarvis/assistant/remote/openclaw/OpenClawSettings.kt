package com.jarvis.assistant.remote.openclaw

/**
 * Snapshot of all OpenClaw connection settings at a point in time.
 * Passed around so callers don't repeatedly hit SettingsStore.
 */
data class OpenClawSettings(
    val enabled:     Boolean,
    val host:        String,
    val port:        Int,
    val secure:      Boolean,
    val authToken:   String,
    val timeoutMs:   Long,
    val modelName:   String  = "openclaw",
    val keyword:     String  = "computer",
    val nodeEnabled:  Boolean = false,
    val deviceId:     String  = "",
    val deviceToken:  String  = "",
    /**
     * When non-blank, overrides the LLM endpoint base URL (e.g. "http://host:8642").
     * The gateway (WebSocket node) always uses the primary host:port above.
     */
    val llmBaseUrl:   String  = "",
) {
    companion object {
        const val DEFAULT_PORT       = 8765
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }

    /** True when the integration is enabled AND a host has been entered. */
    val isFullyConfigured: Boolean
        get() = enabled && host.isNotBlank()
}

/** Live connection status for the OpenClaw node (inbound gateway WebSocket). */
enum class OpenClawNodeStatus(val displayLabel: String) {
    DISABLED("Disabled"),
    CONNECTING("Connecting…"),
    PENDING_APPROVAL("Awaiting admin approval"),
    CONNECTED("Connected as node"),
    RECONNECTING("Reconnecting…"),
    ERROR("Connection error"),
}

/** How a transcript should be routed after local tool matching fails. */
enum class RouteType {
    /** Handled entirely on-device — never sent to OpenClaw. */
    LOCAL_FAST,
    /** Sent to OpenClaw; Jarvis waits silently for the response. */
    REMOTE_FAST,
    /** Sent to OpenClaw; Jarvis speaks a short acknowledgement first. */
    REMOTE_LONG
}

/**
 * Connection health status shown in the Settings UI and used for
 * early-fail checks before remote task execution.
 */
enum class OpenClawConnectionStatus(val displayLabel: String) {
    NOT_CONFIGURED("Not configured"),
    CONNECTING("Connecting…"),
    CONNECTED("Connected"),
    AUTH_FAILED("Auth failed"),
    UNREACHABLE("Unreachable"),
    TIMED_OUT("Timed out"),
    INVALID_RESPONSE("Invalid response")
}
