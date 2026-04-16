package com.jarvis.assistant.followup

/**
 * SlotKey — every distinct piece of information a flow might need.
 *
 * Not all slots apply to every flow type; [ClarificationManager] knows
 * which slots are required or optional per [FlowType].
 */
enum class SlotKey {
    // ── Messaging / Calling ───────────────────────────────────────────────────
    TARGET_CONTACT,     // Name of the person to message or call
    MESSAGE_BODY,       // Body of the SMS or WhatsApp message
    MESSAGE_CHANNEL,    // "sms" or "whatsapp"
    PHONE_TYPE,         // "mobile", "work", or "home"

    // ── Email ─────────────────────────────────────────────────────────────────
    EMAIL_ADDRESS,      // Recipient address (raw email or resolved from contact name)
    EMAIL_SUBJECT,      // Email subject line

    // ── Reminders / Timers ────────────────────────────────────────────────────
    REMINDER_CONTENT,   // What to remind about
    TRIGGER_TIME,       // Absolute epoch-ms stored as String
    TRIGGER_DATE_HINT,  // Partial date context ("tomorrow") before full time is known

    // ── App launch ────────────────────────────────────────────────────────────
    APP_NAME,

    // ── Generic ───────────────────────────────────────────────────────────────
    SUBJECT,            // Generic subject/topic slot
    CONFIRMATION        // "yes" / "no" — used for pending-confirmation flows
}
