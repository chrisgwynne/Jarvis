package com.jarvis.assistant.tools.device

import android.content.Context
import android.util.Log
import com.jarvis.assistant.tools.ContactLookup
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * WhatsAppTool — channel-specific [Tool] that delegates execution to
 * the shared [com.jarvis.assistant.tools.device.messaging.MessagePipeline].
 *
 * Only difference from [SmsTool]:
 *  - declared `name` exposed to the LLM function-calling layer
 *  - registered earlier in [com.jarvis.assistant.tools.framework.ToolRegistry]
 *    so the channel-explicit utterance is routed here when ambiguous
 *  - uses [com.jarvis.assistant.tools.device.messaging.WhatsAppDeliveryAdapter]
 *    instead of SMS
 *
 * Everything else — input validation, contact lookup, body extraction,
 * disambiguation, latency bounds, confirmation handling — lives in the
 * pipeline.  SMS and WhatsApp now have identical execution latency.
 */
class WhatsAppTool(
    private val context: Context,
    private val contacts: ContactLookup
) : Tool {

    companion object { private const val TAG = "WhatsAppTool" }

    override val name = "whatsapp_message"
    override val description = "Open WhatsApp with a pre-filled message to a contact"
    // MEDIUM: confirmed only when confidence tier is < HIGH.  Explicit
    // channel+contact+body utterances ("send a whatsapp to Mike saying hello")
    // are promoted to HIGH in LocalFirstRouter, so they auto-execute.
    override val riskClass = com.jarvis.assistant.tools.framework.RiskClass.MEDIUM

    override fun schema() = ToolSchema(
        name        = name,
        description = "Send a WhatsApp message to a contact. Use this whenever the user " +
            "mentions WhatsApp, WA, or 'whats app' — even if they also say 'message' or " +
            "'text'. Requires WhatsApp installed.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name"    to mapOf("type" to "string", "description" to "Recipient contact name"),
                "message" to mapOf("type" to "string", "description" to "Message body to pre-fill")
            ),
            "required" to listOf("name", "message")
        )
    )

    override fun matches(transcript: String): ToolInput? {
        val intent = MessageIntentParser.parse(transcript) ?: return null
        if (intent.channel != MessageIntentParser.Channel.WHATSAPP) return null
        Log.d(TAG, "[WA_PARSED] recipient=\"${intent.recipient}\" body=\"${intent.body}\" route=WHATSAPP")
        return ToolInput(
            transcript,
            mapOf("name" to intent.recipient, "message" to intent.body)
        )
    }

    private val deliveryAdapter by lazy {
        com.jarvis.assistant.tools.device.messaging.WhatsAppDeliveryAdapter(context)
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        return com.jarvis.assistant.tools.device.messaging.MessagePipeline.run(
            input    = input,
            contacts = contacts,
            adapter  = deliveryAdapter,
        )
    }
}
