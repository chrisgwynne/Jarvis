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
 * BrightnessTool — adjust screen brightness via `Settings.System`.
 *
 * Phrases: "brightness up / down / max / min / 50 percent",
 * "dim the screen", "brighten the screen", "turn brightness to 70 percent".
 *
 * Requires WRITE_SETTINGS (special access — `ACTION_MANAGE_WRITE_SETTINGS`
 * panel must be granted by the user).  Returns Failure with a friendly
 * message + opens the panel if not granted yet.
 */
class BrightnessTool(private val context: Context) : Tool {

    override val name = "brightness"
    override val description = "Adjust the screen brightness."
    override val requiresNetwork = false
    override val requiredPermissions = emptyList<String>() // WRITE_SETTINGS handled at runtime

    private enum class Action { UP, DOWN, MAX, MIN, SET }

    companion object {
        private const val STEP = 32                 // ~12 % of 0..255
        private const val TAG = "BrightnessTool"

        private val UP_RX = Regex(
            """\b(?:turn\s+up|increase|brighten|raise)\s+(?:the\s+)?(?:screen\s+)?brightness\b|\bbrightness\s+up\b|\bbrighten\s+(?:the\s+)?screen\b""",
            RegexOption.IGNORE_CASE,
        )
        private val DOWN_RX = Regex(
            """\b(?:turn\s+down|decrease|dim|lower|reduce)\s+(?:the\s+)?(?:screen\s+)?brightness\b|\bbrightness\s+down\b|\bdim\s+(?:the\s+)?screen\b""",
            RegexOption.IGNORE_CASE,
        )
        private val MAX_RX = Regex(
            """\b(?:max(?:imum)?|full|highest|brightest)\s+(?:screen\s+)?brightness\b|\bbrightness\s+(?:to\s+)?(?:max(?:imum)?|full|highest|100\s*(?:percent|%))\b""",
            RegexOption.IGNORE_CASE,
        )
        private val MIN_RX = Regex(
            """\b(?:min(?:imum)?|lowest|darkest)\s+(?:screen\s+)?brightness\b|\bbrightness\s+(?:to\s+)?(?:min(?:imum)?|lowest|0\s*(?:percent|%))\b""",
            RegexOption.IGNORE_CASE,
        )
        private val SET_RX = Regex(
            """\b(?:set\s+)?brightness\s+(?:to\s+)?(\d{1,3})\s*(?:percent|%)\b""",
            RegexOption.IGNORE_CASE,
        )
    }

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        SET_RX.find(t)?.let { m ->
            val pct = m.groupValues[1].toIntOrNull()?.coerceIn(0, 100) ?: return@let
            return ToolInput(transcript, mapOf("action" to "SET", "percent" to pct.toString()))
        }
        val action = when {
            MAX_RX.containsMatchIn(t)  -> Action.MAX
            MIN_RX.containsMatchIn(t)  -> Action.MIN
            UP_RX.containsMatchIn(t)   -> Action.UP
            DOWN_RX.containsMatchIn(t) -> Action.DOWN
            else -> return null
        }
        return ToolInput(transcript, mapOf("action" to action.name))
    }

    override fun schema() = ToolSchema(
        name        = name,
        description = "Adjust screen brightness.  Action is UP/DOWN/MAX/MIN/SET (with percent).",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "action"  to mapOf("type" to "string", "enum" to listOf("UP","DOWN","MAX","MIN","SET")),
                "percent" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 100),
            ),
            "required" to listOf("action"),
        ),
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        if (!Settings.System.canWrite(context)) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) { Log.w(TAG, "Failed to open write-settings panel", e) }
            return ToolResult.Failure(
                "I need permission to change brightness. I've opened the settings panel — please grant it."
            )
        }

        val action = runCatching { Action.valueOf(input.param("action")) }.getOrNull()
            ?: return ToolResult.Failure("I didn't catch the brightness change.")
        return try {
            val cur = Settings.System.getInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, 128)
            val target = when (action) {
                Action.UP   -> (cur + STEP).coerceAtMost(255)
                Action.DOWN -> (cur - STEP).coerceAtLeast(1)
                Action.MAX  -> 255
                Action.MIN  -> 1
                Action.SET  -> (input.param("percent").toIntOrNull() ?: 50)
                    .coerceIn(0, 100).let { (it * 255 / 100).coerceIn(1, 255) }
            }
            // Take manual mode so the auto sensor doesn't fight the change.
            Settings.System.putInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, target)
            val pct = (target * 100 / 255)
            ToolResult.Success("Brightness $pct percent.")
        } catch (e: Exception) {
            Log.w(TAG, "Brightness change failed", e)
            ToolResult.Failure("That didn't work — couldn't change brightness.")
        }
    }
}
