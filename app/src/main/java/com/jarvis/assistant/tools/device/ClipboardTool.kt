package com.jarvis.assistant.tools.device

import android.content.ClipboardManager
import android.content.Context
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * ClipboardTool — reads the device clipboard aloud.
 *
 * Trigger examples:
 *   "what's on my clipboard"
 *   "what did I copy"
 *   "read my clipboard"
 *   "what have I copied"
 */
class ClipboardTool(private val context: Context) : Tool {

    override val name        = "clipboard"
    override val description = "Read the current clipboard contents aloud"

    private val TRIGGERS = Regex(
        """what(?:'s|\s+is)\s+(?:on\s+)?(?:my\s+)?clipboard""" +
        """|(?:read|get|check)\s+(?:my\s+)?clipboard""" +
        """|what\s+(?:did\s+I|have\s+I)\s+(?:just\s+)?copie?d?""" +
        """|what(?:'s|\s+is)\s+(?:in\s+)?(?:my\s+)?clipboard""",
        RegexOption.IGNORE_CASE
    )

    override fun matches(transcript: String): ToolInput? =
        if (TRIGGERS.containsMatchIn(transcript.trim())) ToolInput(transcript.trim()) else null

    override fun schema() = ToolSchema(
        name        = name,
        description = "Read the current clipboard text aloud.",
        parameters  = mapOf(
            "type"       to "object",
            "properties" to emptyMap<String, Any>(),
            "required"   to emptyList<String>()
        )
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ToolResult.Failure("Clipboard isn't available.")

        if (!clipboard.hasPrimaryClip()) {
            return ToolResult.Success("Your clipboard is empty.")
        }

        val text = clipboard.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?.trim()

        if (text.isNullOrBlank()) {
            return ToolResult.Success("Your clipboard is empty.")
        }

        return if (text.length <= 220) {
            ToolResult.Success("Your clipboard says: $text")
        } else {
            ToolResult.Success(
                "Your clipboard has ${text.length} characters. " +
                "It starts with: ${text.take(180)}…"
            )
        }
    }
}
