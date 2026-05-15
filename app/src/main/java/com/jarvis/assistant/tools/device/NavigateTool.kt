package com.jarvis.assistant.tools.device

import com.jarvis.assistant.maps.MapsCommandRouter
import com.jarvis.assistant.maps.MapsNavigationContext
import com.jarvis.assistant.maps.MapsNavigationContextStore
import com.jarvis.assistant.maps.MapsResult
import com.jarvis.assistant.maps.TravelMode
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * NavigateTool — "take me to X" / "navigate to X" / "open directions to X".
 *
 * Fastest path: hand off to Google Maps' navigation deep link, speak a one-
 * line ack.  Doesn't fetch a route summary because the user's intent is
 * "drive me there", not "tell me how long".
 *
 * Network not strictly required (the underlying intents are local), but the
 * place-name lookup that improves rural accuracy is gated on Places — when
 * unavailable, the navigation handoff still uses the raw destination text.
 */
class NavigateTool(
    private val router: MapsCommandRouter,
    private val navContextStore: MapsNavigationContextStore? = null,
) : Tool {

    override val name = "navigate"
    override val description = "Open Google Maps navigation to a destination"
    override val requiresNetwork = false   // intent works offline; place lookup is best-effort
    override val isLocalFallback = true

    private val MATCH_RE = Regex(
        """(?:take|drive|navigate|guide|lead)\s+(?:me\s+)?to\s+(.+?)\s*\??\s*$""" +
        """|(?:open|start)\s+(?:google\s+)?(?:maps?\s+)?(?:directions|navigation)\s+(?:to|for)\s+(.+?)\s*\??\s*$""" +
        """|(?:open|show)\s+(.+?)\s+(?:on|in)\s+(?:google\s+)?maps?\s*\??\s*$""",
        RegexOption.IGNORE_CASE
    )

    override fun matches(transcript: String): ToolInput? {
        val m = MATCH_RE.find(transcript.trim()) ?: return null
        val dest = (1..m.groupValues.lastIndex)
            .map { m.groupValues[it] }
            .firstOrNull { it.isNotBlank() }
            ?.trim()?.trimEnd('.', '!', '?')
            ?: return null
        return ToolInput(transcript, mapOf("destination" to dest))
    }

    override fun schema() = ToolSchema(
        name = name,
        description = "Open Google Maps navigation to a destination immediately.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "destination" to mapOf(
                    "type" to "string",
                    "description" to "Destination name or address."
                ),
                "mode" to mapOf(
                    "type" to "string",
                    "description" to "Optional: DRIVING, WALKING, BICYCLING, TRANSIT.",
                    "enum" to listOf("DRIVING", "WALKING", "BICYCLING", "TRANSIT")
                )
            ),
            "required" to listOf("destination")
        )
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        val destination = input.param("destination").trim()
        val mode = runCatching { TravelMode.valueOf(input.param("mode")) }.getOrDefault(TravelMode.DRIVING)
        val res = router.handleNavigate(destination, mode)
        if (res.status == MapsResult.Status.OK) {
            navContextStore?.update(MapsNavigationContext(destination = destination, mode = mode))
        }
        return when (res.status) {
            MapsResult.Status.OK -> ToolResult.Success(res.spokenSummary)
            else                 -> ToolResult.Failure(res.spokenSummary)
        }
    }
}
