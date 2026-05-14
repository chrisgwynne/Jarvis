package com.jarvis.assistant.tools.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * BatteryTool — instant local answer for "what's my battery", "battery
 * level", "how much battery", "is the phone charging".  Reads
 * [BatteryManager] directly and never touches the LLM / OpenClaw.
 *
 * Registered early in [com.jarvis.assistant.tools.framework.ToolRegistry]
 * so a battery query never has to wait on a network round-trip.
 */
class BatteryTool(private val context: Context) : Tool {

    override val name = "battery"
    override val description = "Report the device's battery level and charging state"
    override val requiresNetwork = false
    override val isLocalFallback = true
    override val requiredPermissions = emptyList<String>()

    companion object {
        private val PATTERN = Regex(
            """(?ix)
            ^\s*
            (?:
                (?:what(?:'?s|\s+is)\s+(?:my\s+|the\s+)?battery(?:\s+level|\s+percentage|\s+at)?)
              | (?:how\s+much\s+battery(?:\s+do\s+i\s+have|\s+is\s+left|\s+have\s+i\s+got)?)
              | (?:battery\s+(?:level|percentage|status))
              | (?:is\s+(?:the\s+)?phone\s+charging)
              | (?:am\s+i\s+charging)
              | (?:is\s+it\s+charging)
              | (?:battery)
            )
            [\s.?!]*$
            """
        )
    }

    override fun matches(transcript: String): ToolInput? {
        return if (PATTERN.matches(transcript.trim()))
            ToolInput(transcript, emptyMap())
        else null
    }

    override fun schema() = ToolSchema(
        name        = name,
        description = "Report battery percent + charging state from the device. Never go to the LLM for this.",
        parameters  = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val battery: Intent? = context.registerReceiver(null, filter)
        if (battery == null) {
            return ToolResult.Failure("I can't read the battery state right now.")
        }
        val level   = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale   = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else -1

        val status   = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                       status == BatteryManager.BATTERY_STATUS_FULL

        val spoken = when {
            percent < 0 && charging -> "I can't read the level, but it's charging."
            percent < 0             -> "I can't read the battery level."
            charging                -> "Battery's at $percent percent, charging."
            percent <= 15           -> "Battery's at $percent percent — getting low."
            else                    -> "Battery's at $percent percent."
        }
        return ToolResult.Success(spoken)
    }
}
