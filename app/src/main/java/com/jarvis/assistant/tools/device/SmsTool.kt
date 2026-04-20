package com.jarvis.assistant.tools.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import com.jarvis.assistant.tools.ContactLookup
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

class SmsTool(
    private val context: Context,
    private val contacts: ContactLookup
) : Tool {

    override val name = "send_sms"
    override val description = "Send an SMS to a contact"
    override val requiredPermissions = listOf(Manifest.permission.SEND_SMS)
    override val riskClass = com.jarvis.assistant.tools.framework.RiskClass.HIGH

    override fun schema() = ToolSchema(
        name        = name,
        description = "Send an SMS text message to a contact by name.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name"    to mapOf("type" to "string", "description" to "Recipient contact name"),
                "message" to mapOf("type" to "string", "description" to "The message text to send")
            ),
            "required" to listOf("name", "message")
        )
    )

    private val REGEX = Regex(
        """(?:text|message|send (?:a )?(?:text|message)(?: to)?)\s+(.+?)(?:\s+(?:saying|and say|to say|that)\s+(.+))?$""",
        RegexOption.IGNORE_CASE
    )

    override fun matches(transcript: String): ToolInput? {
        val m = REGEX.find(transcript.trim()) ?: return null
        return ToolInput(
            transcript,
            mapOf(
                "name"    to m.groupValues[1].trim(),
                "message" to m.groupValues.getOrElse(2) { "" }.trim()
            )
        )
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val name    = input.param("name")
        val message = input.param("message")
        val contact = contacts.find(name)
            ?: return ToolResult.Failure("No $name in your contacts that I can see.")

        if (message.isBlank()) {
            return try {
                context.startActivity(
                    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${contact.number}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                ToolResult.Success("Opening a message to ${contact.displayName}.")
            } catch (e: Exception) {
                ToolResult.Failure("Couldn't open Messages: ${e.message}")
            }
        }

        return try {
            @Suppress("DEPRECATION")
            val sms = SmsManager.getDefault()
            sms.sendMultipartTextMessage(contact.number, null, sms.divideMessage(message), null, null)
            ToolResult.Success("Message sent to ${contact.displayName}.")
        } catch (e: Exception) {
            ToolResult.Failure("Failed to send the message: ${e.message}")
        }
    }
}
