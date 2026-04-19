package com.jarvis.assistant.tools.device

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

class TimerTool(private val context: Context) : Tool {

    override val name = "set_timer"
    override val description = "Start a countdown timer"

    override fun schema() = ToolSchema(
        name        = name,
        description = "Start a countdown timer for a given duration (e.g. \"5 minutes\", \"1 hour 30 min\").",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "duration" to mapOf("type" to "string", "description" to "Duration, e.g. \"5 minutes\", \"90 seconds\", \"1h 30m\"")
            ),
            "required" to listOf("duration")
        )
    )

    private val REGEX = Regex(
        """(?:set|start|create)\s+(?:a\s+)?timer\s+(?:for\s+)?(.+)""",
        RegexOption.IGNORE_CASE
    )

    override fun matches(transcript: String): ToolInput? {
        val m = REGEX.find(transcript.trim()) ?: return null
        return ToolInput(transcript, mapOf("duration" to m.groupValues[1].trim()))
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val desc    = input.param("duration")
        val seconds = parseDuration(desc)
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (seconds > 0) {
                intent.putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            context.startActivity(intent)
            ToolResult.Success(if (seconds > 0) "Timer set for $desc." else "Opening timer.")
        } catch (e: Exception) {
            ToolResult.Failure("Couldn't set timer: ${e.message}")
        }
    }

    private fun parseDuration(s: String): Int {
        val c = s.trim().lowercase(); var total = 0
        Regex("""(\d+)\s*hour""").find(c)?.let  { total += it.groupValues[1].toInt() * 3600 }
        Regex("""(\d+)\s*min""").find(c)?.let   { total += it.groupValues[1].toInt() * 60 }
        Regex("""(\d+)\s*sec""").find(c)?.let   { total += it.groupValues[1].toInt() }
        if (total == 0) Regex("""^(\d+)$""").find(c.trim())?.let { total = it.groupValues[1].toInt() * 60 }
        return total
    }
}
