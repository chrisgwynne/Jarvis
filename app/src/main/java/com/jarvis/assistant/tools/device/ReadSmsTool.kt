package com.jarvis.assistant.tools.device

import android.Manifest
import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.jarvis.assistant.tools.ContactLookup
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * ReadSmsTool — "read my last text from Mike", "any new messages",
 * "read my texts".  Reads `Telephony.Sms.Inbox` (READ_SMS permission).
 *
 * If a name is given, joins on ContactLookup to filter by that contact's
 * normalised number.  Without a name, returns up to the last 3 inbox
 * messages.
 */
class ReadSmsTool(
    private val context: Context,
    private val contacts: ContactLookup,
    private val messageContextStore: com.jarvis.assistant.session.context.RecentMessageContextStore? = null,
) : Tool {

    override val name = "read_sms"
    override val description = "Read recent SMS messages, optionally from a specific contact."
    override val requiresNetwork = false
    override val requiredPermissions = listOf(Manifest.permission.READ_SMS)

    companion object {
        private const val TAG = "ReadSmsTool"
        private val FROM_RX = Regex(
            """\b(?:read|what(?:'?s|\s+is)|any|show\s+me)\s+(?:my\s+)?(?:last\s+|latest\s+|new\s+|recent\s+)?(?:texts?|messages?|sms)\s+(?:from|by)\s+(.+?)[\s.?!]*$""",
            RegexOption.IGNORE_CASE,
        )
        private val GENERIC_RX = Regex(
            """\b(?:read|what(?:'?s|\s+is)|any|show\s+me)\s+(?:my\s+)?(?:last\s+|latest\s+|new\s+|recent\s+|unread\s+)?(?:texts?|messages?|sms|inbox)\b""",
            RegexOption.IGNORE_CASE,
        )
    }

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        FROM_RX.find(t)?.let { m ->
            return ToolInput(transcript, mapOf("from" to m.groupValues[1].trim()))
        }
        if (GENERIC_RX.containsMatchIn(t)) {
            return ToolInput(transcript, mapOf("from" to ""))
        }
        return null
    }

    override fun schema() = ToolSchema(
        name        = name,
        description = "Read recent SMS messages.  Pass a contact name in 'from' to filter.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "from" to mapOf("type" to "string"),
            ),
        ),
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        val from = input.param("from").trim()
        val numberFilter: String? = if (from.isNotBlank()) {
            val c = contacts.find(from)
                ?: return ToolResult.Failure("I couldn't find $from in your contacts.")
            c.number.filter { it.isDigit() || it == '+' }
                .takeLast(10) // match by last-10 digits (avoid country-code mismatch)
        } else null
        return try {
            val cr = context.contentResolver
            val projection = arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
            )
            cr.query(
                Telephony.Sms.Inbox.CONTENT_URI, projection, null, null,
                "${Telephony.Sms.DATE} DESC",
            ).use { c ->
                if (c == null || !c.moveToFirst()) {
                    return ToolResult.Success("No messages found.")
                }
                val results = mutableListOf<String>()
                do {
                    val addr = c.getString(0) ?: continue
                    if (numberFilter != null) {
                        val normalised = addr.filter { it.isDigit() || it == '+' }
                        if (!normalised.endsWith(numberFilter)) continue
                    }
                    val body = c.getString(1)?.trim() ?: continue
                    val senderName = (numberFilter?.let { from }
                        ?: contacts.find(addr)?.displayName
                        ?: addr)
                    results.add("$senderName: $body")
                    if (results.size >= 3) break
                } while (c.moveToNext())
                if (results.isEmpty()) ToolResult.Success(
                    if (numberFilter != null) "No messages from $from."
                    else "No messages found."
                ) else {
                    // Store the most recent message as context for follow-up replies
                    c.moveToFirst()
                    val firstAddr = c.getString(0) ?: ""
                    val firstBody = c.getString(1)?.trim() ?: ""
                    val resolvedSender = if (from.isNotBlank()) from
                        else contacts.find(firstAddr)?.displayName ?: firstAddr
                    messageContextStore?.set(
                        com.jarvis.assistant.session.context.RecentMessageContext(
                            sender       = resolvedSender,
                            senderNumber = firstAddr,
                            body         = firstBody,
                            channel      = com.jarvis.assistant.session.context.MessageChannel.SMS
                        )
                    )
                    ToolResult.Success(results.joinToString("; "))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Read SMS failed", e)
            ToolResult.Failure("That didn't work — couldn't read your messages.")
        }
    }
}
