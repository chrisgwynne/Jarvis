package com.jarvis.assistant.tools.device

import android.util.Log
import com.jarvis.assistant.remote.openclaw.OpenClawConnectionStatus
import com.jarvis.assistant.remote.openclaw.OpenClawHealthMonitor
import com.jarvis.assistant.remote.openclaw.OpenClawSettingsRepository
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * OpenClawStatusTool — answers "are you connected to OpenClaw?" /
 * "connect to OpenClaw" / "ping OpenClaw" without escalating to the LLM.
 *
 * Before this existed, asking Jarvis about its own brain produced
 * "I don't know what that is" because no tool matched and the local LLM
 * has no built-in knowledge of OpenClaw (a project-internal endpoint).
 *
 * The tool always reports the *live* connection status by running
 * [OpenClawHealthMonitor.check] — no cached values.  Risk class LOW;
 * never confirms.  Pure read; no side-effects.
 *
 * # Phrases that match
 *   - "are you connected to openclaw"
 *   - "is openclaw connected"
 *   - "openclaw status"
 *   - "test openclaw"
 *   - "ping openclaw"
 *   - "connect to openclaw"
 *   - "check openclaw"
 *   - "what's openclaw" / "what is openclaw"
 *
 * All variants are case-insensitive and accept the common mishears
 * ("open claw", "open-claw") thanks to a tolerant regex.
 */
class OpenClawStatusTool(
    private val openClawRepo: OpenClawSettingsRepository
) : Tool {

    companion object {
        private const val TAG = "OpenClawStatusTool"

        /** "openclaw" with optional space/hyphen between "open" and "claw". */
        private val OPEN_CLAW = """open[\s-]?claw"""

        /** Phrases that count as a status query. */
        private val TRIGGER = Regex(
            """(?:""" +
                // "is/are openclaw connected/up/online"
                """(?:is|are)\s+$OPEN_CLAW\s+(?:connected|up|online|alive|reachable|working|running)""" +
            """|""" +
                // "are you connected to openclaw"
                """(?:can|are)\s+you\s+(?:connect|connected|reach|talk)\s+(?:to\s+)?$OPEN_CLAW""" +
            """|""" +
                // imperative status / test / ping / check / connect
                """\b(?:status\s+of\s+|test\s+|ping\s+|check\s+|connect(?:\s+to)?\s+|reconnect\s+(?:to\s+)?)$OPEN_CLAW""" +
            """|""" +
                // "openclaw status"
                """$OPEN_CLAW\s+(?:status|health|connection)""" +
            """|""" +
                // "what / who is openclaw"
                """what(?:'?s|\s+is)\s+$OPEN_CLAW""" +
            """)""",
            RegexOption.IGNORE_CASE
        )
    }

    override val name = "openclaw_status"
    override val description =
        "Check the live connection status to the OpenClaw remote brain " +
            "(your Linux Tailscale-hosted assistant backend)."
    override val riskClass = com.jarvis.assistant.tools.framework.RiskClass.LOW
    override val requiresNetwork = true
    override val isLocalFallback = true   // also runs offline (will just report unreachable)

    override fun schema() = ToolSchema(
        name        = name,
        description = "Report whether OpenClaw — the user's Linux/Tailscale remote " +
            "assistant brain — is reachable.  Use when the user asks about " +
            "OpenClaw, asks 'are you connected', or asks to test/ping/reconnect.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to emptyMap<String, Any>(),
            "required" to emptyList<String>()
        )
    )

    override fun matches(transcript: String): ToolInput? {
        if (!TRIGGER.containsMatchIn(transcript)) return null
        Log.d(TAG, "[OPENCLAW_STATUS_MATCH] \"$transcript\"")
        return ToolInput(transcript)
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val settings = openClawRepo.snapshot()
        if (!settings.isFullyConfigured) {
            Log.d(TAG, "[OPENCLAW_STATUS_NOT_CONFIGURED]")
            return ToolResult.Success(
                "OpenClaw isn't configured yet. Add its URL and auth token in " +
                    "Settings → Advanced."
            )
        }
        val tStart = android.os.SystemClock.elapsedRealtime()
        Log.d(TAG, "[OPENCLAW_STATUS_CHECK_START] host=${settings.host} port=${settings.port}")
        val result = OpenClawHealthMonitor.check(settings)
        val ms = android.os.SystemClock.elapsedRealtime() - tStart
        Log.d(TAG, "[OPENCLAW_STATUS_CHECK_DONE] status=${result.status} +${ms}ms")

        // Include host:port in failure messages so the user knows where to
        // look — the previous "didn't respond in time" said nothing useful.
        val target = "${settings.host}:${settings.port}"
        val spoken = when (result.status) {
            OpenClawConnectionStatus.CONNECTED ->
                "Yes — OpenClaw is online on ${settings.host}."
            OpenClawConnectionStatus.AUTH_FAILED ->
                "OpenClaw at $target answered, but the auth token was rejected. Check it in Settings."
            OpenClawConnectionStatus.TIMED_OUT ->
                "OpenClaw at $target didn't respond. The server may be down or Tailscale isn't connected."
            OpenClawConnectionStatus.UNREACHABLE ->
                "I can't reach OpenClaw at $target — ${result.detail}"
            OpenClawConnectionStatus.INVALID_RESPONSE ->
                "OpenClaw at $target responded but with an unexpected reply. ${result.detail}"
            OpenClawConnectionStatus.NOT_CONFIGURED ->
                "OpenClaw isn't configured yet — add it in Settings → Advanced."
            else ->
                "OpenClaw status: ${result.status}. ${result.detail}"
        }
        return ToolResult.Success(spoken)
    }
}
