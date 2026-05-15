package com.jarvis.assistant.tools.device

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.jarvis.assistant.notifications.JarvisNotificationListener
import com.jarvis.assistant.notifications.MessagingAppCapabilityRegistry
import com.jarvis.assistant.notifications.NotificationEntry
import com.jarvis.assistant.notifications.RecentMessageContext
import com.jarvis.assistant.tools.framework.RiskClass
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * ReplyNotificationTool — sends a voice reply to a buffered notification.
 *
 * Supports both explicit and implicit (context-aware) targeting:
 *
 *   Explicit:
 *     "reply to John saying I'll be there at 6"
 *     "reply to WhatsApp saying on my way"
 *     "respond to Sarah with give me 5 minutes"
 *
 *   Implicit (uses RecentMessageContext — most recent thread):
 *     "reply yes"
 *     "say I'll be there in 10"
 *     "reply with thumbs up"
 *
 *   App-targeted:
 *     "reply to my WhatsApp"
 *     "reply to Slack saying got it"
 *
 * Uses the RemoteInput PendingIntent captured by [JarvisNotificationListener]
 * so the reply goes through the app's own notification channel exactly as if
 * the user tapped Reply in the shade.
 */
class ReplyNotificationTool(private val context: Context) : Tool {

    override val name        = "reply_notification"
    override val description = "Reply to a notification (WhatsApp, SMS, Teams, etc.) by voice"
    override val riskClass   = RiskClass.LOW

    override fun schema() = ToolSchema(
        name        = name,
        description = "Send a voice reply to a buffered notification.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "target"  to mapOf("type" to "string", "description" to "Sender name or app to reply to (omit to use most recent thread)"),
                "message" to mapOf("type" to "string", "description" to "The reply text to send"),
            ),
            "required" to listOf("message")
        )
    )

    // ── Trigger patterns ──────────────────────────────────────────────────────

    // "reply to John saying ..." / "reply saying ..." / "respond to Sarah with ..."
    private val EXPLICIT_REGEX = Regex(
        """(?:reply|respond)\s+(?:to\s+(?<target1>\S+(?:\s+\S+){0,3}?)\s+)?(?:saying|with)\s+(?<msg1>.+)""" +
        """|reply\s+to\s+(?<target2>\S+(?:\s+\S+){0,3})$""",
        RegexOption.IGNORE_CASE
    )

    // "tell Mike I'm on my way" / "tell Cath dinner's ready"
    private val TELL_REGEX = Regex(
        """tell\s+(?<target>\S+(?:\s+\S+){0,2})\s+(?<msg>.+)""",
        RegexOption.IGNORE_CASE
    )

    // "say I'll be there" / "say yes" — implicit thread
    private val SAY_REGEX = Regex(
        """^say\s+(?<msg>.+)$""",
        RegexOption.IGNORE_CASE
    )

    // "message Mike saying ..." / "message Mike [content]"
    private val MESSAGE_REGEX = Regex(
        """^message\s+(?<target>\S+(?:\s+\S+){0,2}?)(?:\s+(?:saying|with)\s+(?<msg>.+))?$""",
        RegexOption.IGNORE_CASE
    )

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()

        // "tell X <message>" — always has a target
        TELL_REGEX.find(t)?.let { m ->
            val target  = m.groups["target"]?.value?.trim() ?: return@let
            val message = m.groups["msg"]?.value?.trim() ?: return@let
            if (message.isNotBlank())
                return ToolInput(t, mutableMapOf("target" to target, "message" to message))
        }

        // Explicit reply patterns
        EXPLICIT_REGEX.find(t)?.let { m ->
            val target  = (m.groups["target1"] ?: m.groups["target2"])?.value?.trim()
            val message = m.groups["msg1"]?.value?.trim() ?: return@let
            if (message.isBlank()) return@let
            val params = mutableMapOf("message" to message)
            if (!target.isNullOrBlank()) params["target"] = target
            return ToolInput(t, params)
        }

        // "say X" — implicit thread, no target needed
        SAY_REGEX.find(t)?.let { m ->
            val message = m.groups["msg"]?.value?.trim() ?: return@let
            if (message.isNotBlank()) return ToolInput(t, mapOf("message" to message))
        }

        // "message X [saying Y]" — compose intent
        MESSAGE_REGEX.find(t)?.let { m ->
            val target  = m.groups["target"]?.value?.trim() ?: return@let
            val message = m.groups["msg"]?.value?.trim()
            val params  = mutableMapOf("target" to target)
            if (!message.isNullOrBlank()) params["message"] = message
            return ToolInput(t, params)
        }

        return null
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    override suspend fun execute(input: ToolInput): ToolResult {
        val message = input.paramOrNull("message")
        val target  = input.paramOrNull("target")

        // "message Mike" with no content → ask what to say
        if (message.isNullOrBlank() && !target.isNullOrBlank()) {
            return ToolResult.Success(
                spokenFeedback      = "What do you want to say to $target?",
                requiresLlmFollowUp = false,
            )
        }

        if (message.isNullOrBlank()) {
            return ToolResult.Failure("No reply message provided.")
        }

        val candidates = JarvisNotificationListener.getRecent().filter { it.canReply }
        if (candidates.isEmpty()) {
            return ToolResult.Failure("No replyable messages in your notifications right now.")
        }

        val entry: NotificationEntry = resolveTarget(target, candidates)
            ?: return ToolResult.Failure(
                "I couldn't find a message from ${target ?: "anyone"} to reply to."
            )

        return try {
            val intent = Intent()
            val bundle = Bundle()
            for (ri in entry.replyRemoteInputs) {
                bundle.putCharSequence(ri.resultKey, message)
            }
            RemoteInput.addResultsToIntent(entry.replyRemoteInputs.toTypedArray(), intent, bundle)
            entry.replyPendingIntent!!.send(context, 0, intent)

            val to = entry.displaySender.ifBlank {
                MessagingAppCapabilityRegistry.displayName(entry.packageName)
            }
            Log.d(TAG, "Replied to $to via ${entry.appName}: \"$message\"")
            ToolResult.Success("Sent to $to.")
        } catch (e: Exception) {
            Log.e(TAG, "Reply send failed", e)
            ToolResult.Failure("Couldn't send the reply — ${e.message}")
        }
    }

    /**
     * Resolve the best matching entry.
     *
     * Priority:
     *   1. If no target: use [RecentMessageContext] (last received message thread).
     *   2. Exact or partial sender/title match.
     *   3. App name match (e.g. "whatsapp" → first WhatsApp notification).
     *   4. Fall back to most recent replyable notification.
     */
    private fun resolveTarget(
        target: String?,
        candidates: List<NotificationEntry>,
    ): NotificationEntry? {
        if (target.isNullOrBlank()) {
            // Prefer active thread from context; fall back to first candidate
            return RecentMessageContext.getLastReplyable() ?: candidates.first()
        }

        // Check if target looks like an app name
        val appCap = MessagingAppCapabilityRegistry.forTriggerName(target)
        if (appCap != null) {
            return candidates.firstOrNull { it.packageName == appCap.packageName }
                ?: candidates.firstOrNull { it.appName.equals(appCap.displayName, ignoreCase = true) }
        }

        // Sender / title match
        val byName = candidates.firstOrNull { e ->
            e.displaySender.contains(target, ignoreCase = true) ||
            e.title.contains(target, ignoreCase = true)
        }
        if (byName != null) return byName

        // Multiple matches for a common first name — prefer most recent
        val multiMatch = candidates.filter { e ->
            e.displaySender.startsWith(target.split(" ").first(), ignoreCase = true)
        }
        if (multiMatch.size == 1) return multiMatch.first()

        // More than one "Mike"? Use context if it matches, else most recent
        if (multiMatch.size > 1) {
            val ctxSender = RecentMessageContext.getLastSender()
            if (ctxSender != null) {
                val ctxMatch = multiMatch.firstOrNull {
                    it.displaySender.contains(ctxSender, ignoreCase = true)
                }
                if (ctxMatch != null) return ctxMatch
            }
            return multiMatch.first()
        }

        return null
    }

    companion object {
        private const val TAG = "ReplyNotificationTool"
    }
}
