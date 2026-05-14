package com.jarvis.assistant.tools.device.messaging

import android.util.Log
import com.jarvis.assistant.tools.ContactLookup
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * MessagePipeline — single execution path shared by [SmsTool] and
 * [WhatsAppTool].  The two tools differ only in their declared name (so the
 * LLM function-calling layer still sees `send_sms` and `whatsapp_message`)
 * and in the [MessageDeliveryAdapter] they pass in.  Everything else —
 * input validation, contact lookup, disambiguation, body extraction,
 * confidence handling — is handled here.
 *
 * # Latency contract
 *
 * The pipeline is bounded by hard timeouts at every stage so the
 * "30-second thinking" failure mode is impossible by construction:
 *
 *   stage             | budget | what happens on timeout
 *   ------------------+--------+--------------------------------------
 *   contact lookup    | 500 ms | falls through to disambiguation /
 *                     |        | "I can't find a number for X"
 *   delivery adapter  | 3 s    | adapter is expected to bound itself;
 *                     |        | the outer 3 s here is the absolute
 *                     |        | ceiling.  Past it we return a clear
 *                     |        | failure rather than waiting forever.
 *
 * # Reuse model
 *
 * Both tools call [run] with the same [contacts] reference (the runtime's
 * single [ContactLookup] instance from Tier A2).  Logs use a `channel`
 * prefix so SMS and WhatsApp traces are distinguishable in logcat.
 */
object MessagePipeline {

    private const val TAG = "MessagePipeline"

    /**
     * Run the shared messaging flow.
     *
     * @param input    Parsed [ToolInput] from the matching tool.  Must
     *                 contain `name` and `message` params; if either is
     *                 blank we ask a local clarification question rather
     *                 than escalating to OpenClaw.
     * @param contacts Shared [ContactLookup] instance.
     * @param adapter  Channel-specific delivery adapter.
     */
    suspend fun run(
        input:    ToolInput,
        contacts: ContactLookup,
        adapter:  MessageDeliveryAdapter,
    ): ToolResult {
        val tStart = android.os.SystemClock.elapsedRealtime()
        val channel = adapter.channelName
        val name    = input.param("name")
        val body    = input.param("message")

        Log.d(TAG, "[MSG_ROUTE_START] channel=$channel name=\"$name\" body_len=${body.length}")

        if (name.isBlank()) {
            Log.d(TAG, "[MSG_MISSING_RECIPIENT] channel=$channel — asking local clarification")
            return ToolResult.Failure("Who should I send the $channel to?")
        }
        if (body.isBlank()) {
            Log.d(TAG, "[MSG_MISSING_BODY] channel=$channel name=\"$name\" — asking local clarification")
            return ToolResult.Failure("What should the $channel to $name say?")
        }

        // Shared contact lookup, bounded.
        Log.d(TAG, "[MSG_CONTACT_LOOKUP_START] name=\"$name\"")
        val contact = withTimeoutOrNull(500L) {
            withContext(Dispatchers.IO) { contacts.find(name) }
        }
        val lookupElapsed = android.os.SystemClock.elapsedRealtime() - tStart
        if (contact == null) {
            contacts.disambiguationPrompt()?.let {
                Log.d(TAG, "[MSG_LOOKUP_AMBIGUOUS] +${lookupElapsed}ms $it")
                return ToolResult.Failure(it)
            }
            Log.d(TAG, "[MSG_LOOKUP_MISS] +${lookupElapsed}ms")
            return ToolResult.Failure(
                "I can't find a number for $name. What's the number?"
            )
        }
        Log.d(TAG, "[MSG_CONTACT_LOOKUP_DONE] +${lookupElapsed}ms " +
            "name=\"${contact.displayName}\" number=\"${contact.number}\"")

        // Delegate to the channel-specific adapter, with an absolute 3 s
        // ceiling so a misbehaving adapter can never wedge the assistant.
        Log.d(TAG, "[MSG_DELIVERY_START] channel=$channel " +
            "+${android.os.SystemClock.elapsedRealtime() - tStart}ms")
        val result = withTimeoutOrNull(3_000L) {
            adapter.send(contact, body)
        } ?: run {
            Log.w(TAG, "[MSG_DELIVERY_TIMEOUT] channel=$channel " +
                "+${android.os.SystemClock.elapsedRealtime() - tStart}ms")
            return ToolResult.Failure(
                when (channel) {
                    "SMS"      -> "Failed to send the SMS — the network adapter timed out."
                    "WHATSAPP" -> "WhatsApp is open with the message ready, but I couldn't " +
                        "press send automatically."
                    else       -> "Message delivery timed out."
                }
            )
        }

        val totalMs = android.os.SystemClock.elapsedRealtime() - tStart
        Log.d(TAG, "[MSG_ROUTE_COMPLETE] channel=$channel total=${totalMs}ms result=${result::class.simpleName}")
        return result
    }
}
