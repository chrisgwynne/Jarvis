package com.jarvis.assistant.tools.device

import android.content.Context
import com.jarvis.assistant.notifications.JarvisNotificationListener
import com.jarvis.assistant.notifications.NotificationEntry
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult

/**
 * ReadNotificationsTool — reads recent system notifications aloud.
 *
 * Triggers on phrases like "read my notifications", "any WhatsApp messages", etc.
 *
 * PARAM: SOURCE — empty string = all apps, "whatsapp" = WhatsApp only,
 *                 "messages" = SMS / messaging apps.
 *
 * Requires notification listener access (not a standard Android permission —
 * checked via [JarvisNotificationListener.isGranted]).
 */
class ReadNotificationsTool(
    private val context: Context
) : Tool {

    override val name        = "read_notifications"
    override val description = "Read recent device notifications aloud"

    // No manifest permission needed here — access is checked via isGranted()
    override val requiredPermissions: List<String> = emptyList()

    // ── Trigger patterns ──────────────────────────────────────────────────────

    private val WHATSAPP_REGEX = Regex(
        """(?:read|check|show|get)\s+(?:my\s+)?whatsapp|whatsapp\s+messages?|whatsapp\s+notifications?""",
        RegexOption.IGNORE_CASE
    )

    private val MESSAGES_REGEX = Regex(
        """(?:unread|any)\s+messages?|read\s+(?:my\s+)?messages?""",
        RegexOption.IGNORE_CASE
    )

    private val NOTIFICATIONS_REGEX = Regex(
        """(?:read|check|show|what(?:'s| are))\s+(?:my\s+)?notifications?|any\s+notifications?""",
        RegexOption.IGNORE_CASE
    )

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        return when {
            WHATSAPP_REGEX.containsMatchIn(t)     -> ToolInput(t, mapOf(PARAM_SOURCE to "whatsapp"))
            MESSAGES_REGEX.containsMatchIn(t)     -> ToolInput(t, mapOf(PARAM_SOURCE to "messages"))
            NOTIFICATIONS_REGEX.containsMatchIn(t) -> ToolInput(t, mapOf(PARAM_SOURCE to ""))
            else                                   -> null
        }
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    override suspend fun execute(input: ToolInput): ToolResult {
        // 1. Permission gate — notification listener access is opt-in by the user
        if (!JarvisNotificationListener.isGranted(context)) {
            return ToolResult.Failure(
                "To read notifications, please grant Jarvis notification access in Settings." +
                " Go to Settings, then Apps, Special app access, Notification access, and enable Jarvis."
            )
        }

        // 2. Fetch notifications, filtered by source if requested
        val source = input.param(PARAM_SOURCE)
        val entries: List<NotificationEntry> = when (source) {
            "whatsapp"  -> JarvisNotificationListener.getFromApp(PKG_WHATSAPP)
            "messages"  -> MESSAGE_PACKAGES.flatMap { JarvisNotificationListener.getFromApp(it) }
                               .sortedByDescending { it.postedAt }
            else        -> JarvisNotificationListener.getRecent()
        }

        // 3. Handle empty state
        if (entries.isEmpty()) {
            val what = when (source) {
                "whatsapp" -> "WhatsApp"
                "messages" -> "messages"
                else       -> ""
            }
            return ToolResult.Success(
                if (what.isEmpty()) "You have no new notifications."
                else "You have no new $what notifications."
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

        return ToolResult.Success(spoken)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun appDisplayName(packageName: String): String = when (packageName) {
        PKG_WHATSAPP                  -> "WhatsApp"
        "com.google.android.gm"       -> "Gmail"
        "com.android.mms"             -> "Messages"
        "com.google.android.apps.messaging" -> "Messages"
        "com.instagram.android"       -> "Instagram"
        "com.twitter.android"         -> "Twitter"
        "com.facebook.katana"         -> "Facebook"
        "com.slack.android"           -> "Slack"
        "com.microsoft.teams"         -> "Teams"
        "com.discord"                 -> "Discord"
        else                          -> packageName.substringAfterLast('.')
            .replaceFirstChar { it.uppercaseChar() }
    }

    companion object {
        private const val PARAM_SOURCE  = "source"
        private const val PKG_WHATSAPP  = "com.whatsapp"
        private const val MAX_SPOKEN    = 5

        private val MESSAGE_PACKAGES = listOf(
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.android.messaging",
            "org.thoughtcrime.securesms",   // Signal
            "com.viber.voip"
        )
    }
}
