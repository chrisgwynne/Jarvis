package com.jarvis.assistant.notifications

/**
 * MessagingAppCapabilityRegistry — single source of truth for per-app messaging
 * metadata: display name, short trigger name, and what operations are supported.
 *
 * Consolidates the duplicated app-name maps scattered across ReadNotificationsTool,
 * ReplyNotificationTool, and ClearNotificationsTool.
 */
object MessagingAppCapabilityRegistry {

    data class Capability(
        val packageName: String,
        val displayName: String,
        /** Short lower-case alias used in voice trigger matching ("whatsapp", "slack"). */
        val triggerName: String,
        val canReply: Boolean = true,
        val canCompose: Boolean = false,
    )

    private val REGISTRY: Map<String, Capability> = listOf(
        Capability("com.whatsapp",                        "WhatsApp",   "whatsapp",  canReply = true,  canCompose = true),
        Capability("com.whatsapp.w4b",                    "WhatsApp",   "whatsapp",  canReply = true,  canCompose = true),
        Capability("com.google.android.apps.messaging",   "Messages",   "messages",  canReply = true,  canCompose = true),
        Capability("com.android.mms",                     "Messages",   "messages",  canReply = true,  canCompose = true),
        Capability("com.android.messaging",               "Messages",   "messages",  canReply = true,  canCompose = true),
        Capability("org.thoughtcrime.securesms",          "Signal",     "signal",    canReply = true,  canCompose = true),
        Capability("org.telegram.messenger",              "Telegram",   "telegram",  canReply = true,  canCompose = false),
        Capability("org.telegram.messenger.beta",         "Telegram",   "telegram",  canReply = true,  canCompose = false),
        Capability("com.microsoft.teams",                 "Teams",      "teams",     canReply = true,  canCompose = false),
        Capability("com.slack.android",                   "Slack",      "slack",     canReply = true,  canCompose = false),
        Capability("com.discord",                         "Discord",    "discord",   canReply = true,  canCompose = false),
        Capability("com.instagram.android",               "Instagram",  "instagram", canReply = true,  canCompose = false),
        Capability("com.facebook.orca",                   "Messenger",  "messenger", canReply = true,  canCompose = false),
        Capability("com.facebook.mlite",                  "Messenger",  "messenger", canReply = true,  canCompose = false),
        Capability("com.viber.voip",                      "Viber",      "viber",     canReply = true,  canCompose = false),
        Capability("com.google.android.gm",               "Gmail",      "gmail",     canReply = false, canCompose = false),
        Capability("com.microsoft.office.outlook",        "Outlook",    "outlook",   canReply = false, canCompose = false),
    ).associateBy { it.packageName }

    /** All known messaging-capable packages (excludes email). */
    private val MESSAGING_PACKAGES: Set<String> = REGISTRY.values
        .filter { it.canReply }
        .map { it.packageName }
        .toSet()

    /** True if [packageName] belongs to a known messaging/chat app (not email). */
    fun isMessagingApp(packageName: String): Boolean = packageName in MESSAGING_PACKAGES

    /** Returns the [Capability] for [packageName], or a generated fallback. */
    fun forPackage(packageName: String): Capability =
        REGISTRY[packageName] ?: Capability(
            packageName = packageName,
            displayName = packageName.substringAfterLast('.').replaceFirstChar { it.uppercaseChar() },
            triggerName = packageName.substringAfterLast('.').lowercase(),
        )

    /** Human-readable app name for display/speech. */
    fun displayName(packageName: String): String = forPackage(packageName).displayName

    /**
     * Find capabilities by voice trigger name ("whatsapp", "slack", "signal").
     * Returns null if no match — caller should fall back to all-apps.
     */
    fun forTriggerName(name: String): Capability? =
        REGISTRY.values.firstOrNull { it.triggerName == name.lowercase() }

    /** All package names for which [canReply] is true. */
    fun replyablePackages(): Set<String> =
        REGISTRY.values.filter { it.canReply }.map { it.packageName }.toSet()
}
