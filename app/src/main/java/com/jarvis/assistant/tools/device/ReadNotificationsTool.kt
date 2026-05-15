package com.jarvis.assistant.tools.device

import android.content.Context
import com.jarvis.assistant.notifications.JarvisNotificationListener
import com.jarvis.assistant.notifications.MessagingAppCapabilityRegistry
import com.jarvis.assistant.notifications.NotificationEntry
import com.jarvis.assistant.notifications.RecentMessageContext
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * ReadNotificationsTool — reads recent notifications and messages aloud.
 *
 * Supported phrases:
 *   All notifications:
 *     "read my notifications" / "any notifications?" / "check my notifications"
 *
 *   Latest message (uses RecentMessageContext → no app needed):
 *     "read my latest message" / "read that again" / "what was that?"
 *
 *   App-specific:
 *     "read my WhatsApps" / "check my WhatsApp" / "WhatsApp messages"
 *     "read my messages" / "any messages?"
 *     "read my Slack" / "read my Telegram"
 *
 *   Sender-specific:
 *     "what did Mike say?" / "any messages from Cath?" / "read messages from John"
 *
 * Spoken response format:
 *   Single:  "Mike said: are you still coming tonight?"
 *   Multi:   "Cath sent 2 messages. First: can you grab milk. Second: where are you?"
 *   Empty:   "No new WhatsApp messages." / "No new notifications."
 */
class ReadNotificationsTool(
    private val context: Context,
    private val messageContextStore: com.jarvis.assistant.session.context.RecentMessageContextStore? = null,
) : Tool {

    override val name        = "read_notifications"
    override val description = "Read recent device notifications or messages aloud"
    override val requiredPermissions: List<String> = emptyList()

    override fun schema() = ToolSchema(
        name        = name,
        description = "Read recent notifications or messages. Filter by app or sender name.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "source" to mapOf("type" to "string", "description" to "App filter: empty = all, 'whatsapp', 'messages', 'slack', etc."),
                "sender" to mapOf("type" to "string", "description" to "Contact name filter, e.g. 'Mike', 'Cath'"),
                "latest" to mapOf("type" to "boolean", "description" to "True to read only the most recent message/notification"),
            ),
            "required" to emptyList<String>()
        )
    )

    // ── Trigger patterns ──────────────────────────────────────────────────────

    // "read my latest message" / "read that again" / "what was that?"
    private val LATEST_MSG_REGEX = Regex(
        """(?:read|what was)\s+(?:my\s+)?latest\s+(?:message|notification)|read\s+that\s+again|what(?:'s|\s+was)\s+that(?:\s+message)?""",
        RegexOption.IGNORE_CASE
    )

    // "what did Mike say?" / "what did Cath send?" / "any messages from John?"
    private val SENDER_REGEX = Regex(
        """what\s+did\s+(?<name1>\S+(?:\s+\S+)?)\s+(?:say|send|write|message)|any\s+(?:messages?|texts?)\s+from\s+(?<name2>\S+(?:\s+\S+)?)|read\s+(?:messages?|texts?)\s+from\s+(?<name3>\S+(?:\s+\S+)?)""",
        RegexOption.IGNORE_CASE
    )

    private val WHATSAPP_REGEX = Regex(
        """(?:read|check|show|get|any)\s+(?:my\s+)?whatsapp|whatsapp\s+messages?|whatsapp\s+notifications?""",
        RegexOption.IGNORE_CASE
    )

    private val MESSAGES_REGEX = Regex(
        """(?:unread|any)\s+messages?|read\s+(?:my\s+)?(?:sms|texts?|messages?)""",
        RegexOption.IGNORE_CASE
    )

    // "read my Slack" / "check my Telegram" / "any Discord messages?"
    private val APP_REGEX = Regex(
        """(?:read|check|any)\s+(?:my\s+)?(?<app>slack|telegram|signal|discord|teams|messenger|instagram|viber)|(?<app2>slack|telegram|signal|discord|teams|messenger)\s+messages?""",
        RegexOption.IGNORE_CASE
    )

    private val NOTIFICATIONS_REGEX = Regex(
        """(?:read|check|show|what(?:'s| are))\s+(?:my\s+)?notifications?|any\s+notifications?""",
        RegexOption.IGNORE_CASE
    )

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()

        if (LATEST_MSG_REGEX.containsMatchIn(t))
            return ToolInput(t, mapOf("latest" to "true"))

        SENDER_REGEX.find(t)?.let { m ->
            val name = (m.groups["name1"] ?: m.groups["name2"] ?: m.groups["name3"])
                ?.value?.trim() ?: return@let
            return ToolInput(t, mapOf("sender" to name))
        }

        if (WHATSAPP_REGEX.containsMatchIn(t))
            return ToolInput(t, mapOf("source" to "com.whatsapp"))

        if (MESSAGES_REGEX.containsMatchIn(t))
            return ToolInput(t, mapOf("source" to "messages"))

        APP_REGEX.find(t)?.let { m ->
            val appName = (m.groups["app"] ?: m.groups["app2"])?.value?.trim()?.lowercase()
                ?: return@let
            val cap = MessagingAppCapabilityRegistry.forTriggerName(appName)
            if (cap != null) return ToolInput(t, mapOf("source" to cap.packageName))
        }

        if (NOTIFICATIONS_REGEX.containsMatchIn(t))
            return ToolInput(t, mapOf<String, String>())

        return null
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    override suspend fun execute(input: ToolInput): ToolResult {
        if (!JarvisNotificationListener.isGranted(context)) {
            return ToolResult.Failure(
                "I need notification access to read messages. Go to Settings, " +
                "Apps, Special app access, Notification access, and enable Jarvis."
            )
        }
        if (!JarvisNotificationListener.isConnected()) {
            return ToolResult.Failure(
                "Notification access is enabled but the listener isn't connected yet. Try again in a moment."
            )
        }

        val latestOnly = input.paramOrNull("latest") == "true"
        val senderFilter = input.paramOrNull("sender")
        val sourceFilter = input.paramOrNull("source")

        // Sender-specific query
        if (!senderFilter.isNullOrBlank()) {
            return readFromSender(senderFilter)
        }

        // "read that again" / "read my latest message"
        if (latestOnly) {
            return readLatest()
        }

        // App-specific
        val entries: List<NotificationEntry> = when {
            sourceFilter == "messages" ->
                SMS_PACKAGES.flatMap { JarvisNotificationListener.getFromApp(it) }
                    .sortedByDescending { it.postedAt }
            !sourceFilter.isNullOrBlank() ->
                JarvisNotificationListener.getFromApp(sourceFilter)
            else ->
                JarvisNotificationListener.getRecent()
        }

        if (entries.isEmpty()) {
            val appLabel = when {
                sourceFilter == "messages"           -> "messages"
                !sourceFilter.isNullOrBlank()        ->
                    MessagingAppCapabilityRegistry.displayName(sourceFilter)
                else                                 -> null
            }
            return ToolResult.Success(
                if (appLabel != null) "No new $appLabel notifications." else "No new notifications."
            )
        }

        // 4. Format up to MAX_SPOKEN entries for TTS
        val toSpeak = entries.take(MAX_SPOKEN)
        val count   = toSpeak.size

        val label = when (source) {
            "whatsapp" -> "WhatsApp notification${if (count != 1) "s" else ""}"
            "messages" -> "message${if (count != 1) "s" else ""}"
            else       -> "notification${if (count != 1) "s" else ""}"
        }

        val lines = toSpeak.joinToString(". ") { entry ->
            val appLabel = appDisplayName(entry.packageName)
            buildString {
                append(appLabel)
                if (entry.title.isNotEmpty()) {
                    append(": ")
                    append(entry.title)
                }
                if (entry.text.isNotEmpty()) {
                    append(" — ")
                    append(entry.text.take(120)) // cap long bodies for TTS
                }
            }
        }

        val intro  = "You have $count $label. "
        val spoken = intro + lines

        // Store the most recent entry as context for follow-up replies
        toSpeak.firstOrNull()?.let { first ->
            val channel = when (source) {
                "whatsapp" -> com.jarvis.assistant.session.context.MessageChannel.WHATSAPP
                else       -> com.jarvis.assistant.session.context.MessageChannel.NOTIFICATION
            }
            messageContextStore?.set(
                com.jarvis.assistant.session.context.RecentMessageContext(
                    sender  = if (first.title.isNotEmpty()) first.title
                              else appDisplayName(first.packageName),
                    body    = first.text,
                    channel = channel,
                )
            )
        }


        return ToolResult.Success(spoken)
    }

    // ── Read helpers ──────────────────────────────────────────────────────────

    private fun readLatest(): ToolResult {
        // Check context first — user might be asking about the message Jarvis just mentioned
        val ctxEntry = RecentMessageContext.getLastReplyable()
            ?: JarvisNotificationListener.getRecentMessages().firstOrNull()
            ?: JarvisNotificationListener.getRecent().firstOrNull()
            ?: return ToolResult.Success("Nothing to read.")

        val spoken = formatSingleEntry(ctxEntry)
        return ToolResult.Success(spoken)
    }

    private fun readFromSender(name: String): ToolResult {
        val entries = JarvisNotificationListener.getMessagesFromSender(name)
            .ifEmpty { JarvisNotificationListener.getRecent().filter {
                it.title.contains(name, ignoreCase = true) ||
                it.text.contains(name, ignoreCase = true)
            }}

        if (entries.isEmpty())
            return ToolResult.Success("No messages from $name.")

        return if (entries.size == 1) {
            ToolResult.Success(formatSingleEntry(entries.first()))
        } else {
            val count  = entries.size.coerceAtMost(MAX_SPOKEN)
            val subset = entries.take(MAX_SPOKEN)
            val lines  = subset.mapIndexed { i, e ->
                val ordinal = if (count == 2) listOf("First", "Second")[i]
                else listOf("First", "Second", "Third", "Fourth", "Fifth").getOrElse(i) { "${i+1}th" }
                "$ordinal: ${e.text.take(120).ifBlank { e.title }}"
            }.joinToString(". ")
            val senderName = entries.first().displaySender.ifBlank { name }
            ToolResult.Success("$senderName sent $count messages. $lines.")
        }
    }

    // ── Formatters ────────────────────────────────────────────────────────────

    private fun formatSingleEntry(entry: NotificationEntry): String {
        val sender  = entry.displaySender.ifBlank { MessagingAppCapabilityRegistry.displayName(entry.packageName) }
        val body    = entry.text.take(200).ifBlank { entry.title }
        return if (entry.isMessaging) "$sender said: $body"
        else "${MessagingAppCapabilityRegistry.displayName(entry.packageName)}: ${entry.title}. $body"
            .trim().trimEnd('.')
            .plus(".")
    }

    private fun formatEntries(entries: List<NotificationEntry>, sourceFilter: String?): String {
        val count = entries.size
        val appLabel = when {
            sourceFilter == "messages"    -> "message${if (count != 1) "s" else ""}"
            !sourceFilter.isNullOrBlank() -> "${MessagingAppCapabilityRegistry.displayName(sourceFilter)} notification${if (count != 1) "s" else ""}"
            else                          -> "notification${if (count != 1) "s" else ""}"
        }

        val lines = entries.joinToString(". ") { entry ->
            buildString {
                val appName = MessagingAppCapabilityRegistry.displayName(entry.packageName)
                if (sourceFilter.isNullOrBlank()) append("$appName: ")
                if (entry.displaySender.isNotBlank() && entry.isMessaging) append("${entry.displaySender} — ")
                else if (entry.title.isNotBlank()) append("${entry.title} — ")
                append(entry.text.take(120).ifBlank { "(no body)" })
            }
        }

        return "You have $count $appLabel. $lines"
    }

    companion object {
        private const val MAX_SPOKEN   = 5

        private val SMS_PACKAGES = listOf(
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.android.messaging",
            "org.thoughtcrime.securesms",
            "com.viber.voip",
        )
    }
}
