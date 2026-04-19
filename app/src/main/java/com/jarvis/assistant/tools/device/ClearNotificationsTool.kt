package com.jarvis.assistant.tools.device

import android.content.Context
import android.util.Log
import com.jarvis.assistant.notifications.JarvisNotificationListener
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * ClearNotificationsTool — voice-dismiss all notifications, or every
 * notification from a named app.
 *
 * Pairs with [com.jarvis.assistant.tools.device.ReadNotificationsTool] which
 * reads them; together those two cover the common "triage the shade" flow
 * without reaching for the phone:
 *
 *   "read my notifications"          → reads recent
 *   "any WhatsApp messages"          → reads only WhatsApp
 *   "clear my notifications"         → dismisses all clearable
 *   "clear WhatsApp notifications"   → dismisses just that app
 *   "dismiss all"                    → same as clear all
 *
 * SAFETY:
 *   Only the user-visible, clearable notifications are cancelled; Android's
 *   framework filters out foreground-service / persistent entries automatically,
 *   so this tool can't, for example, kill Spotify's "now playing" card.
 */
class ClearNotificationsTool(
    private val context: Context
) : Tool {

    override val name        = "clear_notifications"
    override val description = "Dismiss current notifications — all, or from a named app"
    override val requiresNetwork = false
    override val isLocalFallback = true

    // No standard Android permission — access is gated on the user having
    // enabled the NotificationListenerService.  Same pattern as ReadNotificationsTool.
    override val requiredPermissions: List<String> = emptyList()

    companion object {
        private const val TAG = "ClearNotificationsTool"

        /**
         * App-name keywords → package name.  Matched against the utterance
         * AFTER the clear-verb so "clear my WhatsApp notifications" hits
         * the per-app path while "clear my notifications" hits clear-all.
         *
         * Keep this list in sync with ReadNotificationsTool.appDisplayName so
         * the two feel symmetric to the user.
         */
        private val APP_ALIASES = mapOf(
            "whatsapp"          to "com.whatsapp",
            "gmail"             to "com.google.android.gm",
            "email"             to "com.google.android.gm",
            "messages"          to "com.google.android.apps.messaging",
            "text"              to "com.google.android.apps.messaging",
            "sms"               to "com.google.android.apps.messaging",
            "instagram"         to "com.instagram.android",
            "twitter"           to "com.twitter.android",
            "x"                 to "com.twitter.android",
            "facebook"          to "com.facebook.katana",
            "slack"             to "com.slack.android",
            "teams"             to "com.microsoft.teams",
            "discord"           to "com.discord",
            "signal"            to "org.thoughtcrime.securesms"
        )
    }

    private val CLEAR_ALL_RE = Regex(
        """^\s*(?:clear|dismiss|remove|wipe)\s+(?:all\s+(?:of\s+)?)?(?:my\s+|the\s+)?(?:notifications?|alerts?)\s*[.!?]?\s*$""" +
        """|^\s*dismiss\s+all\s*[.!?]?\s*$""" +
        """|^\s*clear\s+(?:the\s+)?(?:notification\s+)?shade\s*[.!?]?\s*$""",
        RegexOption.IGNORE_CASE
    )

    private val CLEAR_APP_RE = Regex(
        """^\s*(?:clear|dismiss|remove|wipe)\s+(?:my\s+|the\s+|all\s+)?(?:my\s+)?(.+?)\s+notifications?\s*[.!?]?\s*$""",
        RegexOption.IGNORE_CASE
    )

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()

        // Clear-all path first so a plain "clear my notifications" doesn't try
        // to be interpreted as "clear {my} notifications" by the per-app regex.
        if (CLEAR_ALL_RE.matches(t)) {
            return ToolInput(transcript, mapOf("scope" to "all"))
        }

        val m = CLEAR_APP_RE.matchEntire(t) ?: return null
        val appPhrase = m.groupValues[1].trim().lowercase()
            .removePrefix("all ").removePrefix("the ").trim()
        if (appPhrase.isBlank()) return null
        val pkg = APP_ALIASES[appPhrase]
            ?: APP_ALIASES.entries.firstOrNull { appPhrase.contains(it.key) }?.value
            ?: return null
        return ToolInput(transcript, mapOf("scope" to "app", "package" to pkg, "label" to appPhrase))
    }

    override fun schema() = ToolSchema(
        name = name,
        description = "Dismiss current notifications — either all of them or just those from one app.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "scope" to mapOf(
                    "type" to "string",
                    "enum" to listOf("all", "app"),
                    "description" to "'all' clears every clearable notification; 'app' requires a package."
                ),
                "package" to mapOf(
                    "type" to "string",
                    "description" to "Package name when scope='app' (e.g. com.whatsapp)."
                )
            ),
            "required" to listOf("scope")
        )
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        if (!JarvisNotificationListener.isGranted(context)) {
            return ToolResult.Failure(
                "To clear notifications, please grant Jarvis notification access in Settings."
            )
        }
        if (!JarvisNotificationListener.isConnected()) {
            // Granted but the OS hasn't bound the listener yet — happens
            // briefly after a reboot or after Jarvis itself restarts.
            return ToolResult.Failure("One sec — notification access is still waking up.")
        }

        return when (input.param("scope")) {
            "all" -> {
                val count = JarvisNotificationListener.clearAll()
                val spoken = when {
                    count < 0    -> "I couldn't clear the notifications."
                    count == 0   -> "Nothing to clear."
                    else         -> "Cleared."
                }
                if (count < 0) ToolResult.Failure(spoken) else ToolResult.Success(spoken, silent = true)
            }
            "app" -> {
                val pkg   = input.param("package")
                val label = input.param("label").takeIf { it.isNotBlank() } ?: appFallbackLabel(pkg)
                val count = JarvisNotificationListener.clearFromApp(pkg)
                val spoken = when {
                    count < 0   -> "I couldn't clear those."
                    count == 0  -> "No $label notifications to clear."
                    else        -> "Cleared $label."
                }
                Log.d(TAG, "clearFromApp($pkg) → $count")
                if (count < 0) ToolResult.Failure(spoken) else ToolResult.Success(spoken, silent = true)
            }
            else -> ToolResult.Failure("I didn't catch what to clear.")
        }
    }

    private fun appFallbackLabel(pkg: String): String =
        APP_ALIASES.entries.firstOrNull { it.value == pkg }?.key ?: "app"
}
