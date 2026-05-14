package com.jarvis.assistant.tools.device.messaging

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import com.jarvis.assistant.tools.ContactLookup
import com.jarvis.assistant.tools.framework.ToolResult

/**
 * SmsDeliveryAdapter — direct send via [SmsManager].
 *
 * Synchronous, no UI, no auto-send dance.  Typically completes in
 * well under 100 ms once the network adapter has the message queued.
 */
class SmsDeliveryAdapter(private val context: Context) : MessageDeliveryAdapter {

    override val channelName = "SMS"

    companion object { private const val TAG = "SmsDeliveryAdapter" }

    override suspend fun send(contact: ContactLookup.Contact, body: String): ToolResult {
        val tStart = android.os.SystemClock.elapsedRealtime()
        Log.d(TAG, "[SMS_DELIVERY_START] to=${contact.displayName} number=${contact.number}")
        return try {
            @Suppress("DEPRECATION")
            val sms = SmsManager.getDefault()
            sms.sendMultipartTextMessage(
                contact.number, null, sms.divideMessage(body), null, null
            )
            Log.d(TAG, "[SMS_DELIVERY_DONE] +${android.os.SystemClock.elapsedRealtime() - tStart}ms")
            ToolResult.Success("Message sent to ${contact.displayName}.")
        } catch (e: Exception) {
            Log.w(TAG, "[SMS_DELIVERY_FAILED] ${e.message}")
            ToolResult.Failure("Failed to send the message: ${e.message}")
        }
    }
}
