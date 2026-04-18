package com.jarvis.assistant.remote.openclaw

/**
 * All ways an OpenClaw remote call can fail.
 * Every subtype carries a [spokenMessage] for immediate TTS delivery.
 */
sealed class OpenClawError(val spokenMessage: String) {

    /** Settings are blank or disabled — call was never attempted. */
    object NotConfigured : OpenClawError(
        "OpenClaw is not configured. Add a host in Settings."
    )

    /**
     * TCP/DNS failure — host not reachable at all.
     * [cause] is retained for logging/diagnostics but kept out of the spoken
     * message to avoid reciting raw socket / exception text to the user.
     */
    class Unreachable(val cause: String = "") : OpenClawError(
        "I couldn't reach your computer. Check the host and that it's on the same network."
    )

    /** Server returned auth_failed or HTTP 401/403. */
    object AuthFailed : OpenClawError(
        "OpenClaw rejected the auth token. Check the token in Settings."
    )

    /** [withTimeoutOrNull] expired before a response arrived. */
    object TimedOut : OpenClawError(
        "OpenClaw didn't respond in time. I'll answer locally."
    )

    /** Server returned status=error with a usable errorCode. */
    class TaskFailed(errorCode: String) : OpenClawError(
        "OpenClaw couldn't complete that: $errorCode"
    )

    /** Response JSON was missing required fields or unparseable. */
    object MalformedResponse : OpenClawError(
        "OpenClaw sent an unexpected response. I'll answer locally."
    )

    /** WebSocket closed mid-stream before a complete response arrived. */
    object ConnectionDropped : OpenClawError(
        "The connection to your computer dropped. I'll answer locally."
    )
}
