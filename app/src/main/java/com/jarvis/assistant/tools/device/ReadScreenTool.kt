package com.jarvis.assistant.tools.device

import android.util.Log
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.llm.LlmException
import com.jarvis.assistant.llm.LlmRouter
import com.jarvis.assistant.llm.Message
import com.jarvis.assistant.runtime.FailurePhrases
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * ReadScreenTool — "what's on the screen?", "read this", "what does it say?".
 *
 * Captures a [com.jarvis.assistant.accessibility.ScreenSnapshot] (text tree
 * plus optional PNG via Accessibility's takeScreenshot API), summarises it
 * through the active LLM, and speaks one short reply.
 *
 * GATING:
 *   * Only fires on explicit on-screen utterances — never auto-triggered.
 *   * Returns a clean failure when the user hasn't granted the Accessibility
 *     service so the tool surfaces in Help and degrades audibly rather than
 *     silently doing nothing.
 *
 * MODEL ROUTE:
 *   When the active provider is vision-capable, the screenshot goes in
 *   alongside the textual node tree (the latter is a much stronger signal
 *   for prose-heavy screens like email or Slack).  When the provider is
 *   text-only, only the node tree is sent — the model still sees the
 *   visible labels, but not chrome / images.
 */
class ReadScreenTool(
    private val llmRouter: LlmRouter
) : Tool {

    override val name           = "read_screen"
    override val description    = "Read or summarise what's currently on screen"
    override val requiresNetwork = true
    override val requiredPermissions = emptyList<String>()

    private val TRIGGERS = Regex(
        """what(?:'s|\s+is)\s+on(?:\s+the)?\s+screen""" +
        """|read\s+(?:the\s+|me\s+the\s+)?(?:screen|message|chat|email|page|article)""" +
        """|what\s+does\s+(?:it|the\s+screen|this)\s+say""" +
        """|summari[sz]e\s+(?:this|the\s+(?:screen|page|article|chat|email))""",
        RegexOption.IGNORE_CASE
    )

    override fun matches(transcript: String): ToolInput? =
        if (TRIGGERS.containsMatchIn(transcript.trim())) ToolInput(transcript.trim()) else null

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = "Capture a snapshot of the foreground screen and summarise its contents in 1-2 short sentences.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "focus" to mapOf(
                    "type" to "string",
                    "description" to "Optional question to focus the summary, e.g. 'what's the price' or 'who sent it'."
                )
            ),
            "required" to emptyList<String>()
        )
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        if (!JarvisAccessibilityService.isConnected()) {
            return ToolResult.Failure(
                "I need the Accessibility permission to see the screen — turn it on " +
                "in Settings → Accessibility → Jarvis."
            )
        }

        val snapshot = JarvisAccessibilityService.snapshot(withScreenshot = true)
            ?: return ToolResult.Failure("I couldn't read the screen.")

        val focus = input.paramOrNull("focus")?.takeIf { it.isNotBlank() }
        val systemMsg = Message(
            role = "system",
            content = "You are Jarvis. Speak as a person, not an assistant. " +
                      "Answer in 1-2 short sentences. No markdown, no bullets, no preamble " +
                      "like 'I see' or 'on the screen'. Just say what's there."
        )
        val userPrompt = buildString {
            if (focus != null) {
                append("Look at the foreground app and answer: ").append(focus).append("\n\n")
            } else {
                append("Briefly summarise what's on screen.\n\n")
            }
            append("Foreground package: ").append(snapshot.foregroundPackage ?: "unknown").append('\n')
            append("Visible text & buttons:\n")
            append(snapshot.toPromptText())
        }
        val userMsg = Message(
            role = "user",
            content = userPrompt,
            imageBase64 = snapshot.screenshotPngBase64
        )

        return try {
            val description = llmRouter.completeSilent(listOf(systemMsg, userMsg))
            ToolResult.Success(description.trim())
        } catch (e: LlmException) {
            Log.w("ReadScreenTool", "summary failed: ${e.message}")
            ToolResult.Failure("I read the screen but couldn't summarise it.")
        } catch (e: Exception) {
            Log.e("ReadScreenTool", "unexpected", e)
            ToolResult.Failure(FailurePhrases.GENERIC)
        }
    }
}
