package com.jarvis.assistant.preferences

/**
 * ResponseDomain — the type of Jarvis response a preference applies to.
 *
 * Domain resolution priority:
 *  1. Explicit keyword in the user utterance ("for calendar, just…")
 *  2. Last tool that ran this session (weather query → WEATHER domain)
 *  3. GENERAL as the catch-all
 */
enum class ResponseDomain(
    val displayName: String,
    /** Keywords that explicitly name this domain in a preference utterance. */
    val triggerKeywords: List<String>,
) {
    WEATHER(
        "Weather",
        listOf("weather", "forecast", "temperature", "degrees", "rain", "conditions",
               "sunny", "cloudy", "wind", "humidity", "precipitation"),
    ),
    CALENDAR(
        "Calendar",
        listOf("calendar", "schedule", "appointment", "appointments", "event", "events",
               "meeting", "meetings", "agenda"),
    ),
    TODOIST(
        "Todoist",
        listOf("todoist", "tasks", "task", "todo", "to-do", "to do", "checklist"),
    ),
    MESSAGES(
        "Messages",
        listOf("message", "messages", "text", "texts", "whatsapp", "sms", "imessage"),
    ),
    NOTIFICATIONS(
        "Notifications",
        listOf("notification", "notifications", "alert", "alerts", "banner"),
    ),
    MAPS(
        "Maps & Navigation",
        listOf("maps", "directions", "route", "eta", "navigation", "drive", "travel time"),
    ),
    HOME_ASSISTANT(
        "Home Assistant",
        listOf("home assistant", "smart home", "lights", "heating", "thermostat",
               "devices", "hass", "ha"),
    ),
    PHONE_COMMANDS(
        "Phone Commands",
        listOf("call", "phone", "dial", "ring"),
    ),
    PROACTIVITY(
        "Proactive messages",
        listOf("proactive", "suggestion", "suggestions", "nudge", "nudges", "reminder",
               "reminders", "morning brief", "daily brief"),
    ),
    LLM_CHAT(
        "General answers",
        listOf("answer", "answers", "response", "responses", "chat", "general",
               "explain", "explanation"),
    ),
    ERRORS(
        "Errors",
        listOf("error", "errors", "failure", "failed", "problem"),
    ),
    GENERAL(
        "General",
        emptyList(),
    );

    companion object {
        /** Tool name → domain mapping for automatic domain tracking in JarvisRuntime. */
        val TOOL_DOMAIN_MAP: Map<String, ResponseDomain> = mapOf(
            "weather"              to WEATHER,
            "get_calendar_events"  to CALENDAR,
            "calendar"             to CALENDAR,
            "todoist"              to TODOIST,
            "maps"                 to MAPS,
            "navigate"             to MAPS,
            "home_assistant"       to HOME_ASSISTANT,
            "smart_home"           to HOME_ASSISTANT,
            "call"                 to PHONE_COMMANDS,
            "open_app"             to PHONE_COMMANDS,
        )

        /**
         * Infer the domain from explicit keywords in [utterance].
         * Returns null if no domain keyword is found — caller should fall back
         * to the last active domain.
         */
        fun fromKeywords(utterance: String): ResponseDomain? {
            val lower = utterance.lowercase()
            // Check in priority order — more specific domains first
            val ordered = listOf(
                HOME_ASSISTANT, TODOIST, CALENDAR, WEATHER, MAPS,
                MESSAGES, NOTIFICATIONS, PHONE_COMMANDS, PROACTIVITY,
                LLM_CHAT, ERRORS,
            )
            return ordered.firstOrNull { domain ->
                domain.triggerKeywords.any { keyword -> lower.contains(keyword) }
            }
        }
    }
}
