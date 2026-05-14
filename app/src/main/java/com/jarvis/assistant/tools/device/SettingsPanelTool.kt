package com.jarvis.assistant.tools.device

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * SettingsPanelTool — open a system Settings page.  "open wifi settings",
 * "open bluetooth settings", "open battery settings", "settings".
 *
 * Just dispatches the right Settings.ACTION_* intent.  Pure navigation —
 * doesn't change anything itself.
 */
class SettingsPanelTool(private val context: Context) : Tool {

    override val name = "settings_panel"
    override val description = "Open a Settings page on the device."
    override val requiresNetwork = false

    companion object {
        private const val TAG = "SettingsPanelTool"
        private val PANELS: List<Pair<Regex, String>> = listOf(
            Regex("""\b(?:open\s+)?(?:wi-?fi|wireless)\s+settings\b""", RegexOption.IGNORE_CASE)
                to Settings.ACTION_WIFI_SETTINGS,
            Regex("""\b(?:open\s+)?bluetooth\s+settings\b""", RegexOption.IGNORE_CASE)
                to Settings.ACTION_BLUETOOTH_SETTINGS,
            Regex("""\b(?:open\s+)?(?:cellular|mobile\s+data|data\s+usage)\s+settings\b""", RegexOption.IGNORE_CASE)
                to Settings.ACTION_DATA_USAGE_SETTINGS,
            Regex("""\b(?:open\s+)?battery\s+settings\b""", RegexOption.IGNORE_CASE)
                to "android.settings.BATTERY_SAVER_SETTINGS",
            Regex("""\b(?:open\s+)?airplane\s+mode\s+settings\b""", RegexOption.IGNORE_CASE)
                to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
            Regex("""\b(?:open\s+)?display\s+settings\b""", RegexOption.IGNORE_CASE)
                to Settings.ACTION_DISPLAY_SETTINGS,
            Regex("""\b(?:open\s+)?sound\s+settings\b""", RegexOption.IGNORE_CASE)
                to Settings.ACTION_SOUND_SETTINGS,
            Regex("""\b(?:open\s+)?location\s+settings\b""", RegexOption.IGNORE_CASE)
                to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            Regex("""\b(?:open\s+)?(?:nfc)\s+settings\b""", RegexOption.IGNORE_CASE)
                to Settings.ACTION_NFC_SETTINGS,
            Regex("""\b(?:open\s+)?(?:notification|notifications)\s+settings\b""", RegexOption.IGNORE_CASE)
                to Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
            Regex("""\b(?:open\s+)?accessibility\s+settings\b""", RegexOption.IGNORE_CASE)
                to Settings.ACTION_ACCESSIBILITY_SETTINGS,
            Regex("""\b(?:open\s+)?date\s+(?:and\s+)?time\s+settings\b""", RegexOption.IGNORE_CASE)
                to Settings.ACTION_DATE_SETTINGS,
            // Bare "open settings" / "open device settings" → main screen
            Regex("""\bopen\s+(?:the\s+)?(?:device\s+|phone\s+|android\s+)?settings\b""", RegexOption.IGNORE_CASE)
                to Settings.ACTION_SETTINGS,
        )
    }

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        // Try the most-specific patterns first; only the bare "open settings"
        // is at the bottom and acts as a fallback.
        for ((rx, action) in PANELS) {
            if (rx.containsMatchIn(t)) {
                return ToolInput(transcript, mapOf("action" to action))
            }
        }
        return null
    }

    override fun schema() = ToolSchema(
        name        = name,
        description = "Open a Settings page.  Action is a Settings.ACTION_* intent name.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "action" to mapOf("type" to "string", "description" to "Settings.ACTION_* intent"),
            ),
            "required" to listOf("action"),
        ),
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        val action = input.param("action").ifBlank { Settings.ACTION_SETTINGS }
        return try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ToolResult.Success("Opening settings.", silent = true)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open settings panel $action", e)
            ToolResult.Failure("That didn't work — couldn't open settings.")
        }
    }
}
