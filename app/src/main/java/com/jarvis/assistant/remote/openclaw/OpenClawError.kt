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
        "Couldn't reach OpenClaw. Check the host and port in Settings, and that the server is running."
    ) {
        init {
            // Structured log so operators can grep for connectivity failures
            // without us leaking the raw cause into the user-facing message.
            // Wrapped so that JVM-only tests (where android.util.Log is not
            // stubbed) can still construct this error.
            if (cause.isNotBlank()) {
                try {
                    android.util.Log.w("OpenClawError", "event=unreachable cause=\"$cause\"")
                } catch (_: Throwable) { /* tests without Robolectric */ }
            }
        }
    }

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
