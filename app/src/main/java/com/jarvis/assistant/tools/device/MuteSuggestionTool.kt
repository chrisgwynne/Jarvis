package com.jarvis.assistant.tools.device

import com.jarvis.assistant.core.decisions.ActionLedger
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * MuteSuggestionTool — consumes phrases like "stop telling me about
 * notifications", "never suggest low battery again", "mute calendar
 * reminders" and flips the corresponding action class in [ActionLedger]
 * to suppressed / unsuppressed.
 *
 * Categories are matched against the canonical action-class labels used
 * everywhere else (BATTERY, REMINDER, CALL, NOTIFICATION, CALENDAR,
 * LOCATION, BRAIN, SECURITY, NETWORK). The user doesn't say those labels
 * verbatim — the tool maps from everyday synonyms.
 */
class MuteSuggestionTool(
    private val ledger: ActionLedger,
) : Tool {

    override val name = "mute_suggestion"
    override val description = "Suppress or re-enable a class of proactive suggestions"
    override val requiresNetwork = false
    override val isLocalFallback = true
    override val requiredPermissions: List<String> = emptyList()

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = "Turn off or on proactive suggestions for a category (battery, reminders, calls, notifications, meetings, location, habits).",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "category" to mapOf("type" to "string"),
                "action" to mapOf("type" to "string", "enum" to listOf("mute", "unmute")),
            ),
            "required" to listOf("category", "action"),
        ),
    )

    override fun matches(transcript: String): ToolInput? {
        val text = transcript.trim().lowercase()
        val muteMatch = MUTE_RE.find(text) ?: UNMUTE_RE.find(text) ?: return null
        val unmute = UNMUTE_RE.containsMatchIn(text)
        val categoryWord = muteMatch.groupValues.getOrNull(1).orEmpty().trim()
        val cls = mapCategory(categoryWord) ?: return null
        return ToolInput(
            transcript = transcript,
            params = mapOf(
                "category" to cls,
                "action" to if (unmute) "unmute" else "mute",
            ),
        )
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val category = input.param("category")
        val action = input.param("action")
        if (category.isBlank()) return ToolResult.Failure("Which category should I mute?")
        return when (action) {
            "unmute" -> {
                ledger.unsuppressClass(category)
                ToolResult.Success("${humanLabel(category)} suggestions back on.")
            }
            else -> {
                ledger.suppressClass(category)
                ToolResult.Success("Muted ${humanLabel(category)} suggestions.")
            }
        }
    }

    private fun mapCategory(word: String): String? = CATEGORY_MAP.entries
        .firstOrNull { (keys, _) -> keys.any { word.contains(it) } }
        ?.value

    private fun humanLabel(cls: String): String = when (cls) {
        "BATTERY" -> "battery"
        "REMINDER" -> "reminder"
        "CALL" -> "call"
        "NOTIFICATION" -> "notification"
        "CALENDAR" -> "calendar"
        "LOCATION" -> "location"
        "BRAIN" -> "habit"
        "SECURITY" -> "security"
        "NETWORK" -> "Wi-Fi"
        else -> cls.lowercase()
    }

    companion object {
        private val MUTE_RE = Regex(
            """(?:stop\s+(?:telling|suggesting|bothering|nagging)|mute|silence|never\s+(?:suggest|tell|mention))\s+(?:me\s+)?(?:about\s+)?(?:the\s+)?([a-z ]+?)(?:\s+(?:again|suggestions?|alerts?|reminders?|notifications?))?\s*\.?\s*$"""
        )
        private val UNMUTE_RE = Regex(
            """(?:unmute|re-?enable|turn\s+back\s+on|start\s+(?:telling|suggesting))\s+(?:me\s+)?(?:about\s+)?(?:the\s+)?([a-z ]+?)(?:\s+(?:again|suggestions?|alerts?|reminders?|notifications?))?\s*\.?\s*$"""
        )
        private val CATEGORY_MAP: Map<Set<String>, String> = mapOf(
            setOf("battery", "charging", "charge") to "BATTERY",
            setOf("reminder") to "REMINDER",
            setOf("call", "missed call", "phone") to "CALL",
            setOf("notification", "alert") to "NOTIFICATION",
            setOf("meeting", "calendar", "agenda") to "CALENDAR",
            setOf("location", "home", "arriving", "arrived") to "LOCATION",
            setOf("habit", "behavioural", "behavioral", "pattern") to "BRAIN",
            setOf("security", "motion") to "SECURITY",
            setOf("wifi", "wi-fi", "network", "ssid") to "NETWORK",
        )
    }
}
