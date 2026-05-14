package com.jarvis.assistant.tools.device

import android.accessibilityservice.AccessibilityService
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * ScreenshotTool — fires the OS global "take screenshot" action through
 * the accessibility service.  The system handles save + thumbnail; this
 * tool just dispatches the gesture, no permission of its own beyond the
 * existing accessibility binding.
 *
 * Falls back with a friendly message when the accessibility service
 * isn't connected — the same path the screen-tap tool uses.
 */
class ScreenshotTool : Tool {

    override val name = "screenshot"
    override val description = "Take a screenshot of the current screen."
    override val requiresNetwork = false

    companion object {
        private val SHOT_RX = Regex(
            """\b(?:take|grab|capture|snap)\s+(?:a\s+)?screen\s*shot\b|\bscreen\s*shot\s+(?:this|that|the\s+screen)\b""",
            RegexOption.IGNORE_CASE,
        )
    }

    override fun matches(transcript: String): ToolInput? =
        if (SHOT_RX.containsMatchIn(transcript.trim())) ToolInput(transcript) else null

    override fun schema() = ToolSchema(
        name        = name,
        description = "Take a screenshot of whatever is on screen right now.",
        parameters  = mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        if (!JarvisAccessibilityService.isConnected()) {
            return ToolResult.Failure(
                "I need accessibility access to take a screenshot. Enable Jarvis in Settings → Accessibility."
            )
        }
        val ok = JarvisAccessibilityService.performGlobalAction(
            AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
        )
        return if (ok) ToolResult.Success("Screenshot taken.")
        else ToolResult.Failure("That didn't work — couldn't take a screenshot.")
    }
}
