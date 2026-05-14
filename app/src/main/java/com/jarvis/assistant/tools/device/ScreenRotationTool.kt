package com.jarvis.assistant.tools.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * ScreenRotationTool — toggle Settings.System.ACCELEROMETER_ROTATION.
 *
 * "lock rotation", "lock the screen rotation", "unlock rotation",
 * "rotate screen automatically", "turn auto-rotate on / off".
 *
 * Needs WRITE_SETTINGS (special access).
 */
class ScreenRotationTool(private val context: Context) : Tool {

    override val name = "screen_rotation"
    override val description = "Lock or unlock screen auto-rotation."
    override val requiresNetwork = false

    companion object {
        private const val TAG = "ScreenRotationTool"
        // "rotate" is the verb shared by auto-rotate/lock-rotation phrasings.
        private val UNLOCK_RX = Regex(
            """\b(?:turn\s+on|enable|allow|unlock)\s+(?:auto[-\s]?)?rotat(?:e|ion)\b|\bauto[-\s]?rotat(?:e|ion)\s+on\b|\bunlock\s+(?:the\s+)?screen\s+rotation\b""",
            RegexOption.IGNORE_CASE,
        )
        private val LOCK_RX = Regex(
            """\b(?:turn\s+off|disable|lock)\s+(?:auto[-\s]?)?rotat(?:e|ion)\b|\bauto[-\s]?rotat(?:e|ion)\s+off\b|\block\s+(?:the\s+)?(?:screen\s+)?rotation\b""",
            RegexOption.IGNORE_CASE,
        )
    }

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        val auto = when {
            UNLOCK_RX.containsMatchIn(t) -> "true"
            LOCK_RX.containsMatchIn(t)   -> "false"
            else -> return null
        }
        return ToolInput(transcript, mapOf("auto" to auto))
    }

    override fun schema() = ToolSchema(
        name        = name,
        description = "Enable or disable auto screen rotation.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "auto" to mapOf("type" to "string", "enum" to listOf("true","false")),
            ),
            "required" to listOf("auto"),
        ),
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        if (!Settings.System.canWrite(context)) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) { Log.w(TAG, "Failed to open write-settings panel", e) }
            return ToolResult.Failure(
                "I need permission to change rotation settings. I've opened the settings panel — please grant it."
            )
        }
        val auto = input.param("auto") == "true"
        return try {
            Settings.System.putInt(context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION, if (auto) 1 else 0)
            ToolResult.Success(
                if (auto) "Auto-rotate on." else "Rotation locked.",
                silent = true,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Rotation toggle failed", e)
            ToolResult.Failure("That didn't work — couldn't change rotation.")
        }
    }

    override val isReversible: Boolean = true
    override suspend fun undo(input: ToolInput, journal: String): ToolResult =
        execute(ToolInput(input.transcript, mapOf("auto" to if (input.param("auto") == "true") "false" else "true")))
}
