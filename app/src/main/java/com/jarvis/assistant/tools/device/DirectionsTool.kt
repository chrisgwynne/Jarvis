package com.jarvis.assistant.tools.device

import com.jarvis.assistant.maps.MapsCommandRouter
import com.jarvis.assistant.maps.MapsResult
import com.jarvis.assistant.maps.TravelMode
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * DirectionsTool — "how do I get to Tesco?" / "give directions to Manchester
 * Airport" / "directions to home".
 *
 * Routes to [MapsCommandRouter.handleDirections] which fetches a short ETA
 * summary, opens Google Maps to the directions screen, and returns the
 * sentence to speak.
 *
 * Network required.  No location permission listed — the underlying
 * Distance Matrix call simply omits the origin and the spoken summary
 * degrades gracefully when location isn't available.
 */
class DirectionsTool(
    private val router: MapsCommandRouter
) : Tool {

    override val name = "directions"
    override val description = "Compute and open directions to a named destination"
    override val requiresNetwork = true

    private val MATCH_RE = Regex(
        """(?:how\s+do\s+i\s+get|how\s+can\s+i\s+get|how\s+to\s+get)\s+to\s+(.+?)\s*\??\s*$""" +
        """|(?:give\s+(?:me\s+)?)?directions\s+(?:to|for)\s+(.+?)\s*\??\s*$""" +
        """|(?:show\s+me\s+)?the\s+route\s+to\s+(.+?)\s*\??\s*$""",
        RegexOption.IGNORE_CASE
    )

    override fun matches(transcript: String): ToolInput? {
        val m = MATCH_RE.find(transcript.trim()) ?: return null
        val dest = (1..m.groupValues.lastIndex)
            .map { m.groupValues[it] }
            .firstOrNull { it.isNotBlank() }
            ?.trim()?.trimEnd('.', '!', '?')
            ?: return null
        // Optional travel mode hint at the tail: "by foot", "by transit"
        val (clean, mode) = extractMode(dest)
        return ToolInput(transcript, mapOf("destination" to clean, "mode" to mode.name))
    }

    override fun schema() = ToolSchema(
        name = name,
        description = "Compute a route summary to a destination and open Google Maps directions.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "destination" to mapOf(
                    "type" to "string",
                    "description" to "The destination name or address."
                ),
                "mode" to mapOf(
                    "type" to "string",
                    "description" to "Optional travel mode: DRIVING, WALKING, BICYCLING, TRANSIT.",
                    "enum" to listOf("DRIVING", "WALKING", "BICYCLING", "TRANSIT")
                )
            ),
            "required" to listOf("destination")
        )
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        val destination = input.param("destination").trim()
        val mode = runCatching { TravelMode.valueOf(input.param("mode")) }.getOrDefault(TravelMode.DRIVING)
        val res = router.handleDirections(destination, mode)
        return when (res.status) {
            MapsResult.Status.OK     -> ToolResult.Success(res.spokenSummary)
            else                     -> ToolResult.Failure(res.spokenSummary)
        }
    }

    /** Extract trailing travel-mode phrases from a destination string. */
    private fun extractMode(raw: String): Pair<String, TravelMode> {
        val lower = raw.lowercase()
        val patterns = listOf(
            " on foot"        to TravelMode.WALKING,
            " walking"        to TravelMode.WALKING,
            " by foot"        to TravelMode.WALKING,
            " by bike"        to TravelMode.BICYCLING,
            " by bicycle"     to TravelMode.BICYCLING,
            " cycling"        to TravelMode.BICYCLING,
            " by transit"     to TravelMode.TRANSIT,
            " by bus"         to TravelMode.TRANSIT,
            " by train"       to TravelMode.TRANSIT,
            " by car"         to TravelMode.DRIVING,
            " driving"        to TravelMode.DRIVING
        )
        for ((suffix, mode) in patterns) {
            if (lower.endsWith(suffix)) {
                return raw.substring(0, raw.length - suffix.length).trim() to mode
            }
        }
        return raw to TravelMode.DRIVING
    }
}
