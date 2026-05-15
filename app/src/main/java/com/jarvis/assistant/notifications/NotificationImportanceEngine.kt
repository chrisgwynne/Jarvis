package com.jarvis.assistant.notifications

import android.app.Notification

/**
 * NotificationImportanceEngine — classifies incoming notifications into five
 * importance tiers so the proactive engine and tools can prioritise what to
 * surface to the user.
 *
 * Levels (highest → lowest):
 *   CRITICAL  — missed calls, urgent keyword, repeated sender
 *   IMPORTANT — messaging app from known contact, calendar reminder
 *   NORMAL    — messaging app (unknown contact), email, Todoist
 *   LOW       — system app, social media like, game notification
 *   SPAM      — marketing, promotional, advertisement keyword detected
 *
 * Rules are evaluated in order; the first matching rule wins.
 */
object NotificationImportanceEngine {

    enum class Importance { CRITICAL, IMPORTANT, NORMAL, LOW, SPAM }

    // ── Keyword lists ─────────────────────────────────────────────────────────

    private val SPAM_KEYWORDS = setOf(
        "offer", "deal", "sale", "discount", "% off", "save", "coupon",
        "promo", "subscribe", "unsubscribe", "free trial", "limited time",
        "click here", "buy now", "shop now", "flash sale", "exclusive",
    )

    private val URGENT_KEYWORDS = setOf(
        "urgent", "emergency", "asap", "help", "please call",
        "important", "need you", "accident", "hospital",
    )

    private val CALL_PACKAGES = setOf(
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.incallui",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "com.microsoft.teams",
        "com.viber.voip",
    )

    private val CALENDAR_PACKAGES = setOf(
        "com.google.android.calendar",
        "com.android.calendar",
        "com.samsung.android.calendar",
        "com.microsoft.office.outlook",
    )

    private val TODOIST_PACKAGES = setOf(
        "com.todoist",
        "com.doist.todoist",
    )

    private val LOW_IMPORTANCE_PACKAGES = setOf(
        "com.android.systemui",
        "com.google.android.gms",
        "com.android.vending",             // Play Store update
        "com.spotify.music",
        "com.google.android.youtube",
    )

    /**
     * Classify a notification.
     *
     * @param entry      The notification to classify.
     * @param repeatCount  How many times this sender has notified without a response
     *                     (used to escalate repeated missed calls / messages).
     */
    fun classify(entry: NotificationEntry, repeatCount: Int = 0): Importance {
        val text   = "${entry.title} ${entry.text}".lowercase()
        val pkg    = entry.packageName

        // 1. Spam heuristic — check before anything else
        if (isSpam(text, pkg)) return Importance.SPAM

        // 2. Missed call / repeated caller → CRITICAL
        val isMissedCall = pkg in CALL_PACKAGES &&
            (entry.title.contains("missed", ignoreCase = true) ||
             entry.text.contains("missed", ignoreCase = true))
        if (isMissedCall) return Importance.CRITICAL
        if (repeatCount >= 2 && pkg in CALL_PACKAGES) return Importance.CRITICAL

        // 3. Urgent keyword in messaging notification → CRITICAL
        if (entry.isMessaging && URGENT_KEYWORDS.any { text.contains(it) })
            return Importance.CRITICAL

        // 4. Messaging app (WhatsApp, SMS, Telegram, etc.) → IMPORTANT
        if (entry.isMessaging) {
            // Repeated unread from same sender escalates
            return if (repeatCount >= 3) Importance.CRITICAL else Importance.IMPORTANT
        }

        // 5. Calendar reminder → IMPORTANT
        if (pkg in CALENDAR_PACKAGES) return Importance.IMPORTANT

        // 6. Task manager (Todoist) → NORMAL
        if (pkg in TODOIST_PACKAGES) return Importance.NORMAL

        // 7. Low-importance system/service packages → LOW
        if (pkg in LOW_IMPORTANCE_PACKAGES) return Importance.LOW

        // 8. Default
        return Importance.NORMAL
    }

    private fun isSpam(lowerText: String, packageName: String): Boolean {
        // Email is the most common source of promo notifications
        val isEmailApp = packageName == "com.google.android.gm" ||
            packageName == "com.microsoft.office.outlook"
        if (!isEmailApp) return false
        return SPAM_KEYWORDS.any { lowerText.contains(it) }
    }

    /** Spoken label for each importance tier, used in summaries. */
    fun label(importance: Importance): String = when (importance) {
        Importance.CRITICAL  -> "urgent"
        Importance.IMPORTANT -> "important"
        Importance.NORMAL    -> "normal"
        Importance.LOW       -> "low priority"
        Importance.SPAM      -> "spam"
    }
}
