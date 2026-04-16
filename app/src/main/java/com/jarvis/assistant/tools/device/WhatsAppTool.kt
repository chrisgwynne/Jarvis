package com.jarvis.assistant.tools.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.tools.ContactLookup
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult

class WhatsAppTool(
    private val context: Context,
    private val contacts: ContactLookup
) : Tool {

    override val name = "whatsapp_message"
    override val description = "Open WhatsApp with a pre-filled message to a contact"

    private val REGEX = Regex(
        """(?:whatsapp|whats\s*app|wa)\s+(.+?)\s+(?:and\s+)?(?:tell(?:\s+them)?|say(?:ing)?|send(?:ing)?|message)\s+(.+)""",
        RegexOption.IGNORE_CASE
    )

    override fun matches(transcript: String): ToolInput? {
        val m = REGEX.find(transcript.trim()) ?: return null
        return ToolInput(
            transcript,
            mapOf(
                "name"    to m.groupValues[1].trim(),
                "message" to m.groupValues[2].trim()
            )
        )
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val name    = input.param("name")
        val message = input.param("message")
        val contact = contacts.find(name)
            ?: return ToolResult.Failure("No $name in your contacts that I can see.")

        // whatsapp://send opens the conversation directly with the message pre-filled.
        // International format without leading +
        val cleanNumber = contact.number.replace(Regex("[^\\d+]"), "")
            .let { if (it.startsWith("+")) it.substring(1) else it }
        val uri = Uri.parse("whatsapp://send?phone=$cleanNumber&text=${Uri.encode(message)}")

        return try {
            JarvisAccessibilityService.arm()
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri)
                    .setPackage("com.whatsapp")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            ToolResult.Success("Sending WhatsApp message to ${contact.displayName}.")
        } catch (e: Exception) {
            JarvisAccessibilityService.disarm()
            ToolResult.Failure("Couldn't open WhatsApp: ${e.message}")
        }
    }
}
