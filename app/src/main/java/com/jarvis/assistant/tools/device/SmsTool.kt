package com.jarvis.assistant.tools.device

import android.Manifest
import android.content.Context
import android.util.Log
import com.jarvis.assistant.tools.ContactLookup
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

class SmsTool(
    private val context: Context,
    private val contacts: ContactLookup
) : Tool {

    companion object { private const val TAG = "SmsTool" }

    override val name = "send_sms"
    override val description = "Send an SMS to a contact"
    override val requiredPermissions = listOf(Manifest.permission.SEND_SMS)
    // MEDIUM: confirmed only when confidence tier is < HIGH.  Explicit
    // utterances with name + body get promoted to HIGH in LocalFirstRouter.
    override val riskClass = com.jarvis.assistant.tools.framework.RiskClass.MEDIUM

    override fun schema() = ToolSchema(
        name        = name,
        description = "Send an SMS text message to a contact by name. " +
            "Do NOT use this when the user says WhatsApp — use whatsapp_message instead.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name"    to mapOf("type" to "string", "description" to "Recipient contact name"),
                "message" to mapOf("type" to "string", "description" to "The message text to send")
            ),
            "required" to listOf("name", "message")
        )
    )

    override fun matches(transcript: String): ToolInput? {
        val intent = MessageIntentParser.parse(transcript) ?: return null
        // Defer when the user specified WhatsApp — WhatsAppTool will pick this up.
        if (intent.channel != MessageIntentParser.Channel.SMS) {
            Log.d(TAG, "[SMS_MATCH_SKIP] channel=${intent.channel} — yielding to WhatsAppTool")
            return null
        }
        Log.d(TAG, "[SMS_PARSED] recipient=\"${intent.recipient}\" body=\"${intent.body}\" route=SMS")
        return ToolInput(
            transcript,
            mapOf("name" to intent.recipient, "message" to intent.body)
        )
    }

    /**
     * Shared messaging pipeline.  All of: input validation, contact lookup,
     * disambiguation, latency bounds, are owned by [MessagePipeline].  The
     * only thing SmsTool contributes is the [SmsDeliveryAdapter].
     */
    private val deliveryAdapter by lazy {
        com.jarvis.assistant.tools.device.messaging.SmsDeliveryAdapter(context)
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        return com.jarvis.assistant.tools.device.messaging.MessagePipeline.run(
            input    = input,
            contacts = contacts,
            adapter  = deliveryAdapter,
        )
    }

}
