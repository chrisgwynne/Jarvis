package com.jarvis.assistant.tools.device

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

class AlarmTool(private val context: Context) : Tool {

    override val name = "set_alarm"
    override val description = "Set an alarm at a specified time"

    override fun schema() = ToolSchema(
        name        = name,
        description = "Set a device alarm clock. Pass a natural-language time like \"7am\" or \"18:30\"; omit for a blank alarm the user will fill in.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "time" to mapOf("type" to "string", "description" to "Time for the alarm, e.g. \"7:30am\" or \"18:00\"")
            ),
            "required" to emptyList<String>()
        )
    )

    private val REGEX = Regex(
        """(?:set|create|add)\s+(?:an?\s+)?alarm(?:\s+(?:for|at)\s+(.+))?""",
        RegexOption.IGNORE_CASE
    )

    override fun matches(transcript: String): ToolInput? {
        val m = REGEX.find(transcript.trim()) ?: return null
        return ToolInput(transcript, mapOf("time" to (m.groupValues.getOrElse(1) { "" }).trim()))
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val timeStr = input.param("time")
        return try {
            val intent  = Intent(AlarmClock.ACTION_SET_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val parsed  = parseTime(timeStr)
            if (parsed != null) {
                intent.putExtra(AlarmClock.EXTRA_HOUR, parsed.first)
                intent.putExtra(AlarmClock.EXTRA_MINUTES, parsed.second)
                intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
            context.startActivity(intent)
            ToolResult.Success(if (timeStr.isBlank()) "Setting an alarm." else "Alarm set for $timeStr.")
        } catch (e: Exception) {
            ToolResult.Failure("Couldn't set alarm: ${e.message}")
        }
    }

    private fun parseTime(s: String): Pair<Int, Int>? {
        val c = s.trim().lowercase()
        Regex("""(\d{1,2}):(\d{2})\s*(am|pm)?""").find(c)?.let { m ->
            var h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
            if (m.groupValues[3] == "pm" && h < 12) h += 12
            if (m.groupValues[3] == "am" && h == 12) h = 0
            return Pair(h, min)
        }
        Regex("""(\d{1,2})\s*(am|pm)""").find(c)?.let { m ->
            var h = m.groupValues[1].toInt()
            if (m.groupValues[2] == "pm" && h < 12) h += 12
            if (m.groupValues[2] == "am" && h == 12) h = 0
            return Pair(h, 0)
        }
        return null
    }
}
