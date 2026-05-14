package com.jarvis.assistant.core.decisions.triggers

/**
 * MessagingAppClassifier — single source of truth for "is this Android app
 * package a messaging app whose notifications a user generally wants Jarvis
 * to read out loud?"
 *
 * Conservative on purpose.  Adding a package here means notifications from
 * it get elevated to InterruptLevel.ACTIVE (i.e. spoken).  Apps that
 * routinely post non-personal notifications (banks, news, calendar) must
 * NOT be on this list, or Jarvis will read them aloud unprompted.
 *
 * Used by [UnreadNotificationTrigger] when
 * [com.jarvis.assistant.voice.VoiceFeatureFlags.Flag
 *   .MESSAGING_NOTIFICATION_ANNOUNCE_ENABLED] is on.
 */
object MessagingAppClassifier {

    /** Packages whose notifications should be spoken when the flag is on. */
    private val MESSAGING_PACKAGES: Set<String> = setOf(
        "com.whatsapp",                                   // WhatsApp
        "com.whatsapp.w4b",                               // WhatsApp Business
        "com.google.android.apps.messaging",              // Google Messages (SMS / RCS)
        "com.samsung.android.messaging",                  // Samsung Messages
        "com.android.mms",                                // legacy AOSP Messages
        "org.thoughtcrime.securesms",                     // Signal
        "org.telegram.messenger",                         // Telegram
        "org.telegram.messenger.web",                     // Telegram Web variant
        "com.facebook.orca",                              // Messenger
        "com.discord",                                    // Discord (DMs are personal)
        "com.microsoft.teams",                            // Teams (chat-only relevance)
        "com.skype.raider",                               // Skype
        "im.threema.app",                                 // Threema
        "ch.protonmail.android",                          // Proton Mail (treated as personal)
    )

    /** Display name to read aloud — friendlier than the raw package id. */
    private val DISPLAY_NAMES: Map<String, String> = mapOf(
        "com.whatsapp"                          to "WhatsApp",
        "com.whatsapp.w4b"                      to "WhatsApp",
        "com.google.android.apps.messaging"     to "Messages",
        "com.samsung.android.messaging"         to "Messages",
        "com.android.mms"                       to "Messages",
        "org.thoughtcrime.securesms"            to "Signal",
        "org.telegram.messenger"                to "Telegram",
        "org.telegram.messenger.web"            to "Telegram",
        "com.facebook.orca"                     to "Messenger",
        "com.discord"                           to "Discord",
        "com.microsoft.teams"                   to "Teams",
        "com.skype.raider"                      to "Skype",
        "im.threema.app"                        to "Threema",
        "ch.protonmail.android"                 to "Proton Mail",
    )

    fun isMessagingApp(packageName: String?): Boolean =
        packageName != null && packageName in MESSAGING_PACKAGES

    /** Spoken name for a messaging app, or null when the package is unknown. */
    fun displayName(packageName: String?): String? =
        packageName?.let { DISPLAY_NAMES[it] }
}
