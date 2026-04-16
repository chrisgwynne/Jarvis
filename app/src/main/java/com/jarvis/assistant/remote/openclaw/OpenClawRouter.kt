package com.jarvis.assistant.remote.openclaw

import android.util.Log
import java.util.UUID

/**
 * Decides whether a transcript should be routed to OpenClaw and, if so,
 * what route type applies.  Then executes the remote call.
 *
 * ROUTING RULES
 * ─────────────
 * LOCAL_FAST  — short queries, small-talk, calculations, simple yes/no
 *               questions, queries mentioning "you" (asking about Jarvis
 *               itself).  Handled entirely on-device.
 *
 * REMOTE_LONG — anything that sounds like it will take meaningful effort:
 *               multi-step research, "write me a …", "find out …",
 *               "generate …", "plan …", "explain in detail".
 *               Jarvis speaks "Looking into that." first so the user isn't
 *               left in silence.
 *
 * REMOTE_FAST — everything else that isn't LOCAL_FAST.  Sent to OpenClaw
 *               silently; reply appears as soon as the server responds.
 */
class OpenClawRouter(
    private val settingsRepo: OpenClawSettingsRepository,
    private val client:       OpenClawClient = OpenClawClient()
) {

    companion object {
        private const val TAG = "OpenClawRouter"

        // Patterns that classify as LOCAL_FAST — keep on-device
        private val LOCAL_PATTERNS = listOf(
            Regex("""^(?:what(?:'s| is) (?:the )?(?:time|date|day)|what day is it)\b""", RegexOption.IGNORE_CASE),
            Regex("""^(?:how are you|what(?:'s| are) you|are you|do you|can you|tell me about yourself)\b""", RegexOption.IGNORE_CASE),
            Regex("""^(?:hi|hello|hey|thanks|thank you|cheers|ok|okay|yes|no|sure|never mind|forget it)\b""", RegexOption.IGNORE_CASE),
            Regex("""^(?:what is \d|calculate|how much is \d|\d+\s*[+\-*/]\s*\d)""", RegexOption.IGNORE_CASE),
            Regex("""^(?:set|turn|switch|enable|disable|open|close|start|stop|play|pause|resume|cancel)\b""", RegexOption.IGNORE_CASE),
            Regex("""(?:alarm|timer|reminder|note|call|text|message|photo|selfie|record)\b""", RegexOption.IGNORE_CASE)
        )

        // Patterns that classify as REMOTE_LONG — long-running research tasks
        private val LONG_PATTERNS = listOf(
            Regex("""^(?:write|draft|compose|create)\s+(?:me\s+)?(?:a|an|the)\s+\w""", RegexOption.IGNORE_CASE),
            Regex("""^(?:explain|describe|summarise|summarize)\s+(?:in detail|everything|how|why|what)\b""", RegexOption.IGNORE_CASE),
            Regex("""^(?:find out|research|look into|investigate|analyse|analyze)\b""", RegexOption.IGNORE_CASE),
            Regex("""^(?:plan|outline|generate|build me|give me a list of|list all)\b""", RegexOption.IGNORE_CASE),
            Regex("""^(?:what(?:'s| is) the (?:best|difference|meaning|history|background))\b""", RegexOption.IGNORE_CASE)
        )
    }

    /**
     * Returns true when OpenClaw is enabled and fully configured.
     * Call before [execute] to avoid needlessly opening a WebSocket.
     */
    fun shouldRoute(): Boolean = settingsRepo.isConfigured()

    /**
     * Classify a transcript into a [RouteType].
     * Always returns a value — falls back to [RouteType.REMOTE_FAST].
     */
    fun classify(transcript: String): RouteType {
        val t = transcript.trim()
        if (LOCAL_PATTERNS.any { it.containsMatchIn(t) }) return RouteType.LOCAL_FAST
        if (LONG_PATTERNS.any  { it.containsMatchIn(t) }) return RouteType.REMOTE_LONG
        return RouteType.REMOTE_FAST
    }

    /**
     * Route [transcript] to OpenClaw and return the execution result.
     *
     * Returns [OpenClawExecutionResult.Bypassed] when:
     *   - OpenClaw is not configured
     *   - The transcript classifies as [RouteType.LOCAL_FAST]
     *
     * Callers are responsible for:
     *   - Speaking the REMOTE_LONG acknowledgement ("Looking into that.") BEFORE
     *     calling this function — this router does not do that to keep concerns
     *     separated.
     */
    suspend fun execute(
        transcript: String,
        sessionId:  String
    ): OpenClawExecutionResult {
        if (!shouldRoute()) return OpenClawExecutionResult.Bypassed

        val settings = settingsRepo.snapshot()
        val route    = classify(transcript)
        Log.d(TAG, "Route=$route for: ${transcript.take(60)}")

        if (route == RouteType.LOCAL_FAST) return OpenClawExecutionResult.Bypassed

        val request = OpenClawRequest(
            requestId      = UUID.randomUUID().toString(),
            transcript     = transcript,
            routeType      = route,
            sessionId      = sessionId,
            timeoutMs      = settings.timeoutMs,
            isVoiceRequest = true
        )

        return client.send(settings, request)
    }
}
