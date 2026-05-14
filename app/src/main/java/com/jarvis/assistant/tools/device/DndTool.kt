package com.jarvis.assistant.tools.device

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * DndTool — toggle Do Not Disturb.  "turn on do not disturb", "dnd off",
 * "silence the phone", "stop silent mode".
 *
 * Needs ACCESS_NOTIFICATION_POLICY (special access — opens the panel when
 * not granted).
 */
class DndTool(private val context: Context) : Tool {

    override val name = "dnd"
    override val description = "Turn Do Not Disturb on or off."
    override val requiresNetwork = false
    override val requiredPermissions = emptyList<String>()

    companion object {
        private const val TAG = "DndTool"
        private val ON_RX = Regex(
            """\b(?:turn\s+on|enable|activate|start)\s+(?:do\s+not\s+disturb|dnd|silent\s+mode)\b|\b(?:do\s+not\s+disturb|dnd|silent\s+mode)\s+on\b|\bsilence\s+(?:my\s+|the\s+)?phone\b""",
            RegexOption.IGNORE_CASE,
        )
        private val OFF_RX = Regex(
            """\b(?:turn\s+off|disable|stop|cancel|end)\s+(?:do\s+not\s+disturb|dnd|silent\s+mode)\b|\b(?:do\s+not\s+disturb|dnd|silent\s+mode)\s+off\b|\bunsilence\s+(?:my\s+|the\s+)?phone\b""",
            RegexOption.IGNORE_CASE,
        )
    }

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        val on = when {
            ON_RX.containsMatchIn(t)  -> "true"
            OFF_RX.containsMatchIn(t) -> "false"
            else -> return null
        }
        return ToolInput(transcript, mapOf("on" to on))
    }

    override fun schema() = ToolSchema(
        name        = name,
        description = "Turn Do Not Disturb on or off.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "on" to mapOf("type" to "string", "enum" to listOf("true","false")),
            ),
            "required" to listOf("on"),
        ),
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        val nm = context.getSystemService(NotificationManager::class.java)
            ?: return ToolResult.Failure("Notification service unavailable.")
        if (!nm.isNotificationPolicyAccessGranted) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) { Log.w(TAG, "Failed to open DND access panel", e) }
            return ToolResult.Failure(
                "I need Do Not Disturb access. I've opened the settings panel — please grant it."
            )
        }
        val on = input.param("on") == "true"
        return try {
            nm.setInterruptionFilter(
                if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else NotificationManager.INTERRUPTION_FILTER_ALL
            )
            ToolResult.Success(if (on) "Do Not Disturb on." else "Do Not Disturb off.", silent = true)
        } catch (e: Exception) {
            Log.w(TAG, "DND toggle failed", e)
            ToolResult.Failure("That didn't work — couldn't change Do Not Disturb.")
        }
    }

    override val isReversible: Boolean = true
    override suspend fun undo(input: ToolInput, journal: String): ToolResult =
        execute(ToolInput(input.transcript, mapOf("on" to if (input.param("on") == "true") "false" else "true")))
}
