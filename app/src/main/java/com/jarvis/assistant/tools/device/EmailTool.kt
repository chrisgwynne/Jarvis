package com.jarvis.assistant.tools.device

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

class EmailTool(private val context: Context) : Tool {

    override val name = "send_email"
    override val description = "Compose and send an email to a contact"
    override val requiredPermissions = listOf(Manifest.permission.READ_CONTACTS)

    override fun schema() = ToolSchema(
        name        = name,
        description = "Open an email compose screen to a contact, optionally with a subject and body.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name"    to mapOf("type" to "string", "description" to "Recipient name from contacts"),
                "subject" to mapOf("type" to "string", "description" to "Email subject line"),
                "body"    to mapOf("type" to "string", "description" to "Email body text")
            ),
            "required" to listOf("name")
        )
    )

    private val REGEX = Regex(
        """(?:send|email|write|compose)(?:\s+an?)?\s+email(?:\s+to)?\s+(.+?)(?:\s+(?:saying|about|with subject)\s+(.+))?$""",
        RegexOption.IGNORE_CASE
    )

    override fun matches(transcript: String): ToolInput? {
        val m = REGEX.find(transcript.trim()) ?: return null
        return ToolInput(
            transcript,
            mapOf(
                "name" to m.groupValues[1].trim(),
                "body" to m.groupValues.getOrElse(2) { "" }.trim()
            )
        )
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val name    = input.param("name")
        val subject = input.param("subject")
        val body    = input.param("body")

        val email = findEmail(context.contentResolver, name)

        return try {
            val uri = if (email != null) {
                Uri.parse("mailto:${Uri.encode(email)}")
            } else {
                Uri.parse("mailto:")
            }
            val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                if (subject.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
                if (body.isNotBlank()) putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val to = if (email != null) name else "your email app"
            ToolResult.Success("Opening email compose to $to.")
        } catch (e: Exception) {
            ToolResult.Failure("Couldn't open email app: ${e.message}")
        }
    }

    private fun findEmail(resolver: ContentResolver, name: String): String? {
        val nameLower = name.lowercase()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Email.DATA,
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME
        )
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        ) ?: return null

        var bestEmail: String? = null
        var bestScore = Int.MAX_VALUE

        cursor.use {
            val emailIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Email.DATA)
            val nameIdx  = it.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
            while (it.moveToNext()) {
                val email       = it.getString(emailIdx) ?: continue
                val contactName = it.getString(nameIdx)?.lowercase() ?: continue
                val diff        = Math.abs(contactName.length - nameLower.length)
                if (diff < bestScore) {
                    bestScore = diff
                    bestEmail = email
                }
            }
        }
        return bestEmail
    }
}
