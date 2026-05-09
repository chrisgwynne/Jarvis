package com.jarvis.assistant.tools.device

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.jarvis.assistant.notifications.JarvisNotificationListener
import com.jarvis.assistant.notifications.NotificationEntry
import com.jarvis.assistant.tools.framework.RiskClass
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * ReplyNotificationTool — sends a voice reply to a buffered notification.
 *
 * Trigger examples:
 *   "reply to John saying I'll be there at 6"
 *   "reply to WhatsApp saying on my way"
 *   "respond to Sarah with give me 5 minutes"
 *   "reply saying sounds good"  ← replies to most recent replyable notification
 *
 * Uses the RemoteInput PendingIntent captured by [JarvisNotificationListener]
 * so the reply goes through the app's own notification channel (WhatsApp,
 * Messages, Teams, etc.) exactly as if the user tapped Reply in the shade.
 */
class ReplyNotificationTool(private val context: Context) : Tool {

    override val name        = "reply_notification"
    override val description = "Reply to a notification (WhatsApp, SMS, Teams, etc.) by voice"
    override val riskClass   = RiskClass.LOW

    override fun schema() = ToolSchema(
        name        = name,
        description = "Send a voice reply to a buffered notification. Specify target (sender name or app) and the reply message.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "target"  to mapOf("type" to "string", "description" to "Sender name or app to reply to (optional — omit to reply to most recent)"),
                "message" to mapOf("type" to "string", "description" to "The reply text to send"),
            ),
            "required" to listOf("message")
        )
    )

    // "reply to John saying I'll be there" / "reply saying sounds good" /
    // "respond to Sarah with give me 5 minutes"
    private val REGEX = Regex(
        """(?:reply|respond)\s+(?:to\s+(?<target1>\S+(?:\s+\S+){0,3}?)\s+)?(?:saying|with)\s+(?<msg1>.+)""" +
        """|reply\s+to\s+(?<target2>\S+(?:\s+\S+){0,3})$""",
        RegexOption.IGNORE_CASE
    )

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        val m = REGEX.find(t) ?: return null
        val target  = (m.groups["target1"] ?: m.groups["target2"])?.value?.trim()
        val message = m.groups["msg1"]?.value?.trim() ?: return null
        if (message.isBlank()) return null
        val params = mutableMapOf("message" to message)
        if (!target.isNullOrBlank()) params["target"] = target
        return ToolInput(t, params)
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val message = input.param("message").ifBlank {
            return ToolResult.Failure("No reply message provided.")
        }
        val target = input.paramOrNull("target")

        // Find the best matching replyable notification
        val candidates = JarvisNotificationListener.getRecent().filter { it.canReply }
        if (candidates.isEmpty()) {
            return ToolResult.Failure("No replyable messages in your notifications right now.")
        }

        val entry: NotificationEntry = if (target.isNullOrBlank()) {
            candidates.first()
        } else {
            candidates.firstOrNull { e ->
                e.title.contains(target, ignoreCase = true) ||
                appDisplayName(e.packageName).contains(target, ignoreCase = true)
            } ?: candidates.first()
        }

        return try {
            val intent = Intent()
            val bundle = Bundle()
            for (ri in entry.replyRemoteInputs) {
                bundle.putCharSequence(ri.resultKey, message)
            }
            RemoteInput.addResultsToIntent(entry.replyRemoteInputs.toTypedArray(), intent, bundle)
            entry.replyPendingIntent!!.send(context, 0, intent)

            val to = entry.title.ifBlank { appDisplayName(entry.packageName) }
            Log.d(TAG, "Replied to $to: \"$message\"")
            ToolResult.Success("Replied to $to.")
        } catch (e: Exception) {
            Log.e(TAG, "Reply send failed", e)
            ToolResult.Failure("Couldn't send the reply — ${e.message}")
        }
    }

    private fun appDisplayName(pkg: String): String = when (pkg) {
        "com.whatsapp"                       -> "WhatsApp"
        "com.google.android.apps.messaging"  -> "Messages"
        "com.android.mms"                    -> "Messages"
        "com.android.messaging"              -> "Messages"
        "org.thoughtcrime.securesms"         -> "Signal"
        "com.microsoft.teams"                -> "Teams"
        "com.slack.android"                  -> "Slack"
        "com.discord"                        -> "Discord"
        "com.instagram.android"              -> "Instagram"
        else -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercaseChar() }
    }

    companion object {
        private const val TAG = "ReplyNotificationTool"
    }
}
