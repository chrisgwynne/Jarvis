package com.jarvis.assistant.tools.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jarvis.assistant.tools.ContactLookup
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

class CallTool(
    private val context: Context,
    private val contacts: ContactLookup
) : Tool {

    override val name = "call_contact"
    override val description = "Make a phone call to a contact by name"
    override val requiredPermissions = listOf(Manifest.permission.CALL_PHONE)

    override fun schema() = ToolSchema(
        name        = name,
        description = "Place a voice call to a contact by name. The user's device must have that contact saved.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name" to mapOf("type" to "string", "description" to "Contact name to call, e.g. \"Mum\" or \"Chris\"")
            ),
            "required" to listOf("name")
        )
    )

    private val REGEX = Regex(
        """(?:call|phone|ring|dial)\s+(.+?)(?:\s+(?:for me|please|now))?$""",
        RegexOption.IGNORE_CASE
    )

    override fun matches(transcript: String): ToolInput? {
        val m = REGEX.find(transcript.trim()) ?: return null
        return ToolInput(transcript, mapOf("name" to m.groupValues[1].trim()))
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val name    = input.param("name")
        val contact = contacts.find(name)
            ?: return ToolResult.Failure("No $name in your contacts that I can see.")
        return try {
            context.startActivity(
                Intent(Intent.ACTION_CALL, Uri.parse("tel:${contact.number}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            ToolResult.Success("Calling ${contact.displayName}.")
        } catch (e: Exception) {
            ToolResult.Failure("Failed to place the call: ${e.message}")
        }
    }
}
