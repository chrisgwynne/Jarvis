package com.jarvis.assistant.followup

/**
 * FlowType — identifies the kind of multi-turn conversational flow in progress.
 */
enum class FlowType {
    MESSAGE_DRAFT,      // SMS / WhatsApp composition
    EMAIL_DRAFT,        // Email composition — opens default mail app
    CALL_CONTACT,       // Phone call with optional phone-type disambiguation
    REMINDER_CREATION,  // Internal reminder (via ReminderRepository)
    TIMER_CREATION,     // Internal countdown timer
    APP_LAUNCH,         // Open a specific app
    CLARIFICATION       // Generic single-question clarification
}
