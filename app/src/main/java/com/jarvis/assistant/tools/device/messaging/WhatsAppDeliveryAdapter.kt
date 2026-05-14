package com.jarvis.assistant.tools.device.messaging

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.tools.ContactLookup
import com.jarvis.assistant.tools.device.PhoneNumberNormalizer
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.voice.VoiceFeatureFlags
import kotlinx.coroutines.withTimeoutOrNull

/**
 * WhatsAppDeliveryAdapter — opens WhatsApp's compose-send screen via deep
 * link.  Auto-send is delegated to [JarvisAccessibilityService] which taps
 * the Send button when the WhatsApp window opens.
 *
 * Latency contract (matches [SmsDeliveryAdapter]):
 *  - Function returns within 1 s for the prefill path.
 *  - When auto-send is on, the accessibility click happens asynchronously
 *    after this function has already returned.  We never block waiting
 *    for the click — JarvisAccessibilityService handles its own 3 s
 *    timeout via its `ARMED_TIMEOUT_MS` runnable.
 *  - On any failure (no WhatsApp installed, system slow), we return a
 *    clear failure within 1 s rather than hanging the assistant.
 */
class WhatsAppDeliveryAdapter(private val context: Context) : MessageDeliveryAdapter {

    override val channelName = "WHATSAPP"

    companion object { private const val TAG = "WaDeliveryAdapter" }

    override suspend fun send(contact: ContactLookup.Contact, body: String): ToolResult {
        val tStart = android.os.SystemClock.elapsedRealtime()
        val waNumber = PhoneNumberNormalizer.toWhatsAppFormat(contact.number)
        Log.d(TAG, "[WA_NUMBER_NORMALISED] raw=\"${contact.number}\" wa=\"$waNumber\"")

        val uri = Uri.parse("whatsapp://send?phone=$waNumber&text=${Uri.encode(body)}")

        // Decide auto-send eligibility BEFORE arming so we don't leave a
        // half-armed Accessibility flag dangling on cold paths.
        val autoSendFlagOn = VoiceFeatureFlags.isEnabled(
            VoiceFeatureFlags.Flag.WHATSAPP_AUTO_SEND_ENABLED
        )
        val a11yConnected = JarvisAccessibilityService.isConnected()
        val willAutoSend  = autoSendFlagOn && a11yConnected
        Log.d(TAG, "[WA_DELIVERY_START] to=${contact.displayName} number=$waNumber " +
            "autoSend=$willAutoSend (flag=$autoSendFlagOn a11y=$a11yConnected)")

        if (willAutoSend) {
            JarvisAccessibilityService.arm(pkg = "com.whatsapp")
            Log.d(TAG, "[WA_AUTOSEND_PENDING_CREATED] pkg=com.whatsapp")
        }

        // Bounded launch.  The system Intent dispatch is normally < 50 ms;
        // the 1 s timeout is a hard ceiling — past that we fail-fast.
        Log.d(TAG, "[WA_INTENT_START] +${android.os.SystemClock.elapsedRealtime() - tStart}ms")
        val launched = withTimeoutOrNull(1_000L) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, uri)
                        .setPackage("com.whatsapp")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (e: Exception) {
                Log.w(TAG, "[WA_INTENT_FAILED] ${e.message}")
                false
            }
        }
        if (launched != true) {
            JarvisAccessibilityService.disarm()
            Log.w(TAG, "[WA_DELIVERY_FAILED] startActivity timed out or threw")
            return ToolResult.Failure(
                "I couldn't open WhatsApp in time. It may not be installed."
            )
        }

        val elapsedMs = android.os.SystemClock.elapsedRealtime() - tStart
        Log.d(TAG, "[WA_DELIVERY_DONE] launched=true +${elapsedMs}ms")

        return when {
            willAutoSend -> {
                // Accessibility will tap Send when WhatsApp's window opens
                // (≤ 3 s in JarvisAccessibilityService's own timeout).  We
                // do NOT wait on the click — speak a short ack so the user
                // perceives Jarvis as responsive, then return.  If the click
                // fails, JarvisAccessibilityService logs WA_AUTOSEND_TIMEOUT
                // and the user can see the prefilled message in WhatsApp.
                Log.d(TAG, "[WA_PREFILL_SUCCESS] autosend_pending — speaking short ack")
                ToolResult.Success("Sending it now.")
            }
            autoSendFlagOn && !a11yConnected -> {
                Log.d(TAG, "[WA_AUTOSEND_FAILED_ACCESSIBILITY_OFF]")
                ToolResult.Success(
                    "WhatsApp is open with the message ready. Enable Jarvis " +
                        "Accessibility to auto-send next time."
                )
            }
            else -> {
                Log.d(TAG, "[WA_PREFILL_SUCCESS] manual_send_expected")
                ToolResult.Success("WhatsApp is open with the message ready.")
            }
        }
    }
}
