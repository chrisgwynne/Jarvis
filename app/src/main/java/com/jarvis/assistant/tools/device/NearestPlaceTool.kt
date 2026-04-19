package com.jarvis.assistant.tools.device

import android.Manifest
import com.jarvis.assistant.maps.MapsCommandRouter
import com.jarvis.assistant.maps.MapsResult
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * NearestPlaceTool — "what's the nearest petrol station?", "where's the
 * nearest pharmacy?", "find me a coffee shop nearby".
 *
 * Resolves the category, asks [MapsCommandRouter.handleNearest] to do the
 * actual Places lookup, and offers a one-line follow-up by storing the
 * matched place so the next "yes" / "open it" routes to a directions handoff.
 *
 * Network required (Places HTTP).  Coarse location permission required —
 * we never fabricate a "nearest" without a real fix.
 */
class NearestPlaceTool(
    private val router: MapsCommandRouter
) : Tool {

    override val name = "nearest_place"
    override val description = "Find the nearest place of a given category (pharmacy, petrol, coffee, etc.)"
    override val requiresNetwork = true
    override val requiredPermissions = listOf(Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * Last successful nearest result + the user's category, kept across one
     * follow-up turn so "yes / open it / take me there" routes to a
     * directions handoff without re-asking which place.
     */
    @Volatile private var pendingFollowUp: PendingFollowUp? = null

    private data class PendingFollowUp(val category: String, val place: com.jarvis.assistant.maps.PlaceMatch)

    private val MATCH_RE = Regex(
        """(?:where(?:'s|\s+is)|what(?:'s|\s+is)|find\s+(?:me\s+)?)\s+(?:the\s+|a\s+|my\s+)?nearest\s+(.+?)\s*\??\s*$""" +
        """|nearest\s+(.+?)\s*\??\s*$""" +
        """|(?:closest)\s+(.+?)\s*\??\s*$""",
        RegexOption.IGNORE_CASE
    )

    private val FOLLOW_YES_RE = Regex(
        """^\s*(yes|yeah|yep|sure|please|go|open it|directions|take me there|let's go)\s*\.?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val FOLLOW_NO_RE = Regex(
        """^\s*(no|nope|nah|cancel|never\s*mind)\s*\.?\s*$""",
        RegexOption.IGNORE_CASE
    )

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        // Follow-up branch — only claim when something is actually pending and
        // the utterance is a tight yes/no, so "yeah, set a timer" isn't hijacked.
        if (pendingFollowUp != null && t.split(Regex("\\s+")).size <= 4) {
            if (FOLLOW_YES_RE.matches(t)) return ToolInput(transcript, mapOf("followup" to "yes"))
            if (FOLLOW_NO_RE.matches(t))  return ToolInput(transcript, mapOf("followup" to "no"))
        }
        val m = MATCH_RE.find(t) ?: return null
        val category = (1..m.groupValues.lastIndex)
            .map { m.groupValues[it] }
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.trimEnd('.', '!', '?')
            ?: return null
        return ToolInput(transcript, mapOf("category" to category))
    }

    override fun schema() = ToolSchema(
        name = name,
        description = "Find the nearest place of a category (e.g. pharmacy, petrol station, coffee shop).",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "category" to mapOf(
                    "type" to "string",
                    "description" to "The category to search for, e.g. 'pharmacy' or 'petrol station'."
                )
            ),
            "required" to listOf("category")
        )
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        // Follow-up: convert "yes / open it" into a navigate handoff.
        input.paramOrNull("followup")?.let { answer ->
            val pending = pendingFollowUp
            pendingFollowUp = null
            if (pending == null) return ToolResult.Success("")
            return when (answer) {
                "yes" -> when (val res = router.handleNavigate(pending.place.name)) {
                    else -> ToolResult.Success(res.spokenSummary, silent = res.spokenSummary.isBlank())
                }
                else -> ToolResult.Success("Okay.")
            }
        }

        val category = input.param("category").trim()
        val res = router.handleNearest(category)
        return when (res.status) {
            MapsResult.Status.OK -> {
                // Stash the result for the optional follow-up.  The routing
                // tool layer doesn't have a Listening-after-speak primitive,
                // so just append the question to the spoken summary and
                // arm the follow-up matcher for the next turn.
                val place = res.placeMatch
                if (place != null) {
                    pendingFollowUp = PendingFollowUp(category, place)
                    ToolResult.Success(res.spokenSummary + " Want directions?")
                } else {
                    ToolResult.Success(res.spokenSummary)
                }
            }
            MapsResult.Status.NEEDS_LOCATION,
            MapsResult.Status.FAILED,
            MapsResult.Status.AMBIGUOUS -> ToolResult.Failure(res.spokenSummary)
        }
    }
}
