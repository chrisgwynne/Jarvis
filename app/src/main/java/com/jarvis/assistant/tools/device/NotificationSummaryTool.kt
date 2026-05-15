package com.jarvis.assistant.tools.device

import android.content.Context
import com.jarvis.assistant.notifications.JarvisNotificationListener
import com.jarvis.assistant.notifications.MessagingAppCapabilityRegistry
import com.jarvis.assistant.notifications.NotificationEntry
import com.jarvis.assistant.notifications.NotificationImportanceEngine
import com.jarvis.assistant.notifications.NotificationImportanceEngine.Importance
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * NotificationSummaryTool — gives an intelligent summary of pending notifications
 * grouped by importance, app, or person.
 *
 * Triggers:
 *   "what notifications have I got?"
 *   "anything important?"
 *   "summarise my notifications"
 *   "what have I missed?"
 *   "any important messages?"
 *   "catch me up"
 *
 * Responses (no LLM — all local):
 *   "You've got 3 WhatsApps, one missed call, and an email from Gmail."
 *   "Nothing important. Mostly app noise."
 *   "Mike sent 2 messages and Cath sent one."
 */
class NotificationSummaryTool(
    private val context: Context,
) : Tool {

    override val name        = "notification_summary"
    override val description = "Summarise pending notifications grouped by importance or app"
    override val requiredPermissions: List<String> = emptyList()

    override fun schema() = ToolSchema(
        name        = name,
        description = "Give a spoken summary of what notifications are waiting.",
        parameters  = mapOf(
            "type"       to "object",
            "properties" to mapOf(
                "filter" to mapOf(
                    "type"        to "string",
                    "enum"        to listOf("all", "important", "messages"),
                    "description" to "all = everything, important = CRITICAL+IMPORTANT only, messages = messaging apps only",
                )
            ),
            "required" to emptyList<String>()
        )
    )

    // ── Trigger patterns ──────────────────────────────────────────────────────

    private val IMPORTANT_REGEX = Regex(
        """anything\s+important|any\s+important|what(?:'s|\s+is)\s+important|important\s+(?:messages?|notifications?)""",
        RegexOption.IGNORE_CASE
    )

    private val SUMMARY_REGEX = Regex(
        """what\s+(?:notifications?|messages?)\s+(?:have\s+i\s+(?:got|missed)|are\s+there)|summaris[ez]\s+(?:my\s+)?notifications?|what\s+have\s+i\s+missed|catch\s+me\s+up|any\s+(?:new\s+)?notifications?""",
        RegexOption.IGNORE_CASE
    )

    private val MESSAGES_SUMMARY_REGEX = Regex(
        """how\s+many\s+messages|any\s+messages\?|messages\s+waiting|unread\s+messages""",
        RegexOption.IGNORE_CASE
    )

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        if (IMPORTANT_REGEX.containsMatchIn(t))
            return ToolInput(t, mapOf("filter" to "important"))
        if (MESSAGES_SUMMARY_REGEX.containsMatchIn(t))
            return ToolInput(t, mapOf("filter" to "messages"))
        if (SUMMARY_REGEX.containsMatchIn(t))
            return ToolInput(t, mapOf("filter" to "all"))
        return null
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    override suspend fun execute(input: ToolInput): ToolResult {
        if (!JarvisNotificationListener.isGranted(context)) {
            return ToolResult.Failure("I need notification access to summarise your notifications.")
        }
        if (!JarvisNotificationListener.isConnected()) {
            return ToolResult.Failure("Notification listener isn't connected yet. Try again in a moment.")
        }

        val filter = input.paramOrNull("filter") ?: "all"
        val all    = JarvisNotificationListener.getRecent()

        if (all.isEmpty()) {
            return ToolResult.Success("No notifications waiting.")
        }

        val entries = when (filter) {
            "messages"  -> all.filter { it.isMessaging }
            "important" -> all.filter {
                val imp = NotificationImportanceEngine.classify(it)
                imp == Importance.CRITICAL || imp == Importance.IMPORTANT
            }
            else -> all
        }

        if (entries.isEmpty()) {
            return ToolResult.Success(
                when (filter) {
                    "messages"  -> "No new messages."
                    "important" -> "Nothing important. Mostly app noise."
                    else        -> "No notifications."
                }
            )
        }

        val spoken = buildSummary(entries, filter)
        return ToolResult.Success(spoken)
    }

    // ── Summary builder ───────────────────────────────────────────────────────

    private fun buildSummary(entries: List<NotificationEntry>, filter: String): String {
        // Group by app
        val byApp = entries.groupBy { it.packageName }

        // For messaging-only queries, group by sender instead
        if (filter == "messages" || entries.all { it.isMessaging }) {
            return buildMessagesSummary(entries)
        }

        // For "important", lead with urgency tier
        if (filter == "important") {
            return buildImportantSummary(entries)
        }

        // General summary: group by app, list counts
        val parts = byApp.entries
            .sortedByDescending { (_, v) -> v.size }
            .take(5)
            .map { (pkg, items) ->
                val name  = MessagingAppCapabilityRegistry.displayName(pkg)
                val count = items.size
                when {
                    pkg.contains("dialer") || pkg.contains("phone") ->
                        if (items.any { it.title.contains("missed", ignoreCase = true) })
                            "${count} missed call${if (count != 1) "s" else ""}"
                        else "${count} call notification${if (count != 1) "s" else ""}"
                    items.first().isMessaging ->
                        "$count $name message${if (count != 1) "s" else ""}"
                    count == 1 ->
                        "${items.first().title.ifBlank { "a notification" }} from $name"
                    else ->
                        "$count $name notification${if (count != 1) "s" else ""}"
                }
            }

        return when (parts.size) {
            1    -> "You've got ${parts[0]}."
            2    -> "You've got ${parts[0]} and ${parts[1]}."
            else -> {
                val head = parts.dropLast(1).joinToString(", ")
                "You've got $head, and ${parts.last()}."
            }
        }
    }

    private fun buildMessagesSummary(entries: List<NotificationEntry>): String {
        val bySender = entries.groupBy { it.displaySender.ifBlank { it.title } }
        val parts    = bySender.entries.take(5).map { (sender, msgs) ->
            val count = msgs.size
            "$sender sent $count message${if (count != 1) "s" else ""}"
        }
        return when (parts.size) {
            0    -> "No new messages."
            1    -> "${parts[0]}."
            2    -> "${parts[0]} and ${parts[1]}."
            else -> "${parts.dropLast(1).joinToString(", ")}, and ${parts.last()}."
        }
    }

    private fun buildImportantSummary(entries: List<NotificationEntry>): String {
        val critical  = entries.filter { NotificationImportanceEngine.classify(it) == Importance.CRITICAL }
        val important = entries.filter { NotificationImportanceEngine.classify(it) == Importance.IMPORTANT }

        val parts = mutableListOf<String>()

        critical.groupBy { it.displaySender.ifBlank { it.title } }.entries.take(3).forEach { (name, items) ->
            val desc = items.first().let {
                if (it.title.contains("missed", ignoreCase = true)) "missed call from $name"
                else "${items.size} urgent message${if (items.size != 1) "s" else ""} from $name"
            }
            parts += desc
        }

        important.groupBy { it.packageName }.entries.take(3).forEach { (pkg, items) ->
            val name  = MessagingAppCapabilityRegistry.displayName(pkg)
            val count = items.size
            parts += "$count $name message${if (count != 1) "s" else ""}"
        }

        if (parts.isEmpty()) return "Nothing important."

        return when (parts.size) {
            1    -> "You've got ${parts[0]}."
            2    -> "You've got ${parts[0]} and ${parts[1]}."
            else -> "You've got ${parts.dropLast(1).joinToString(", ")}, and ${parts.last()}."
        }
    }
}
