package com.jarvis.assistant.tools.device

import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.location.CurrentLocationProvider
import com.jarvis.assistant.tools.ContactLookup
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * ShareLocationTool — "share my location with Mike", "send my location to
 * Mum on WhatsApp".  Resolves the contact, fetches the current GPS fix,
 * and fires either an SMS or a WhatsApp share intent containing a
 * https://maps.google.com/?q=lat,lon URL.
 *
 * The actual Send tap is left to the user (no auto-send) — sending a
 * location is medium-risk and we don't have the same accessibility
 * affordance as plain WhatsApp text.
 */
class ShareLocationTool(
    private val context: Context,
    private val contacts: ContactLookup,
    private val locationProvider: CurrentLocationProvider,
) : Tool {

    override val name = "share_location"
    override val description = "Share your current location with a contact via SMS or WhatsApp."
    override val requiresNetwork = false

    companion object {
        private val SHARE_RX = Regex(
            """\bshare\s+(?:my\s+)?location\s+(?:with|to)\s+(.+?)(?:\s+on\s+(whatsapp|sms|text|message))?[\s.?!]*$""",
            RegexOption.IGNORE_CASE,
        )
        private val SEND_RX = Regex(
            """\bsend\s+(?:my\s+)?location\s+(?:to)\s+(.+?)(?:\s+on\s+(whatsapp|sms|text|message))?[\s.?!]*$""",
            RegexOption.IGNORE_CASE,
        )
    }

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        val m = SHARE_RX.find(t) ?: SEND_RX.find(t) ?: return null
        val rawTarget = m.groupValues[1].trim()
        val channel = m.groupValues.getOrNull(2)?.lowercase()
            ?.let { if (it == "whatsapp") "whatsapp" else "sms" }
            ?: "sms"
        if (rawTarget.isBlank()) return null
        return ToolInput(transcript, mapOf("contact" to rawTarget, "channel" to channel))
    }

    override fun schema() = ToolSchema(
        name        = name,
        description = "Share current location with a contact via SMS or WhatsApp.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "contact" to mapOf("type" to "string"),
                "channel" to mapOf("type" to "string", "enum" to listOf("sms","whatsapp")),
            ),
            "required" to listOf("contact","channel"),
        ),
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        val name = input.param("contact")
        val channel = input.param("channel").ifBlank { "sms" }
        val match = contacts.find(name)
            ?: return ToolResult.Failure("I couldn't find $name in your contacts.")
        val number = match.number.takeIf { it.isNotBlank() }
            ?: return ToolResult.Failure("${match.displayName} doesn't have a number on file.")
        locationProvider.refresh(highAccuracy = false)
        val loc = locationProvider.lastResult
            ?: return ToolResult.Failure("I can't get a location fix right now.")
        val url = "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
        val body = "My location: $url"
        return try {
            val intent = when (channel) {
                "whatsapp" -> Intent(Intent.ACTION_VIEW).apply {
                    data = android.net.Uri.parse(
                        "https://wa.me/${number.filter { it.isDigit() || it == '+' }}?text=" +
                            java.net.URLEncoder.encode(body, "UTF-8")
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                else -> Intent(Intent.ACTION_SENDTO).apply {
                    data = android.net.Uri.parse("smsto:$number")
                    putExtra("sms_body", body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
            ToolResult.Success("Sharing your location with ${match.displayName} on $channel — just hit send.")
        } catch (e: Exception) {
            Log.w("ShareLocationTool", "Share location failed", e)
            ToolResult.Failure("That didn't work — couldn't share your location.")
        }
    }
}
