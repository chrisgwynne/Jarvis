package com.jarvis.assistant.tools.device

import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TimeTool — the dedicated local handler for time-of-day and date queries.
 *
 * "What time is it?", "what's the time", "tell me the time", "current time",
 * "what's the date", "what day is it" all land here.  These questions must
 * **never** be routed through OpenClaw, MiniMax, or any memory retrieval:
 * the answer is on the device, the latency budget is < 200 ms, and the
 * LLM's date can drift if it falls back to a training-time anchor.
 *
 * Registered very early in [com.jarvis.assistant.tools.framework.ToolRegistry]
 * (after WhereAmITool / EndCallTool) so nothing else can intercept the
 * phrasing.  Pure local — no permissions, no network.
 */
class TimeTool : Tool {

    override val name = "time"
    override val description = "Report the current time or date from the device clock"
    override val requiresNetwork = false
    override val isLocalFallback = true
    override val requiredPermissions = emptyList<String>()

    companion object {
        /**
         * Time-of-day phrasings.  Permissive on punctuation noise (handled
         * by [com.jarvis.assistant.voice.routing.TranscriptNormalizer] in
         * the router) but anchored on full-utterance form so we don't
         * intercept compound commands like "set an alarm for what time mum
         * gets home".
         */
        private val TIME_RE = Regex(
            """(?ix)
            ^\s*
            (?:
                what(?:'?s|\s+is)\s+the\s+time
              | what\s+time\s+is\s+it(?:\s+now)?
              | what\s+time\s+is\s+it\s+right\s+now
              | what\s+time
              | (?:the\s+)?current\s+time
              | (?:please\s+)?tell\s+me\s+the\s+time
              | time\s+please
              | got\s+the\s+time
              | do\s+you\s+have\s+the\s+time
              | (?:please\s+)?give\s+me\s+the\s+time
            )
            [\s.?!]*$
            """
        )

        /**
         * Date / day-of-week phrasings.  Same anchoring discipline.
         */
        private val DATE_RE = Regex(
            """(?ix)
            ^\s*
            (?:
                what(?:'?s|\s+is)\s+(?:today'?s\s+|the\s+)?date
              | what\s+is\s+today
              | what\s+day\s+is\s+(?:it|today)
              | what\s+(?:day\s+of\s+the\s+week\s+is\s+it|day\s+of\s+the\s+week)
              | tell\s+me\s+(?:today'?s\s+date|the\s+date|what\s+day\s+it\s+is)
              | (?:please\s+)?give\s+me\s+the\s+date
            )
            [\s.?!]*$
            """
        )
    }

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        return when {
            TIME_RE.matches(t) -> ToolInput(transcript, mapOf("kind" to "time"))
            DATE_RE.matches(t) -> ToolInput(transcript, mapOf("kind" to "date"))
            else               -> null
        }
    }

    override fun schema() = ToolSchema(
        name        = name,
        description = "Answer 'what time is it' / 'what's the date' from the device clock. Never go to the LLM for these.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "kind" to mapOf("type" to "string", "enum" to listOf("time", "date"))
            )
        )
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        val kind = input.param("kind").ifBlank { "time" }
        val now = Date()
        return when (kind) {
            "date" -> ToolResult.Success(formatDate(now))
            else   -> ToolResult.Success(formatTime(now))
        }
    }

    /** Format the time naturally — "It's twenty past three." style for round numbers, fall back to digits otherwise. */
    internal fun formatTime(now: Date): String {
        val twelveHr = SimpleDateFormat("h:mm a", Locale.UK).format(now)
            .replace("AM", "in the morning")
            .replace("PM", run {
                val hour = SimpleDateFormat("HH", Locale.UK).format(now).toInt()
                when {
                    hour < 17 -> "in the afternoon"
                    hour < 21 -> "in the evening"
                    else      -> "at night"
                }
            })
        return "It's $twelveHr."
    }

    internal fun formatDate(now: Date): String {
        // "Tuesday, the 14th of May." — natural spoken form.
        val dayName  = SimpleDateFormat("EEEE", Locale.UK).format(now)
        val dayNum   = SimpleDateFormat("d", Locale.UK).format(now).toInt()
        val month    = SimpleDateFormat("MMMM", Locale.UK).format(now)
        val suffix   = ordinalSuffix(dayNum)
        return "$dayName, the $dayNum$suffix of $month."
    }

    private fun ordinalSuffix(day: Int): String {
        if (day in 11..13) return "th"
        return when (day % 10) {
            1    -> "st"
            2    -> "nd"
            3    -> "rd"
            else -> "th"
        }
    }
}
