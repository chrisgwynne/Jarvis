package com.jarvis.assistant.security

enum class ActionType(val displayName: String) {
    SET_REMINDER("Set Reminder"),
    SET_ALARM("Set Alarm"),
    SET_TIMER("Set Timer"),
    OPEN_APP("Open App"),
    CALL_CONTACT("Call Contact"),
    END_CALL("End Call"),
    SEND_MESSAGE("Send SMS"),
    SEND_WHATSAPP("Send WhatsApp"),
    SEND_EMAIL("Send Email"),
    TOGGLE_TORCH("Toggle Torch"),
    ADJUST_VOLUME("Adjust Volume"),
    CONTROL_MEDIA("Control Media"),
    MUSIC_SEARCH("Music Search"),
    READ_MEMORY("Read Memory"),
    SAVE_APP_IMAGE_OUTPUT("Save Generated Image"),
    SEARCH_WEB("Search Web"),
    SHOPPING_LIST("Shopping List"),
    READ_CALENDAR("Read Calendar"),
    READ_NOTIFICATIONS("Read Notifications"),
    CLEAR_NOTIFICATIONS("Clear Notifications"),
    DAILY_BRIEFING("Daily Briefing"),
    HELP("Help"),
    CAPTURE_PHOTO("Capture Photo"),
    ANALYZE_CAMERA_VIEW("Analyze Camera View"),
    AUDIO_RECORDING("Audio Recording"),
    MUTE_SUGGESTION("Mute Suggestion"),
    SAVE_ROUTINE("Save Routine"),
    RUN_ROUTINE("Run Routine"),
    LIST_ROUTINES("List Routines"),
    DELETE_ROUTINE("Delete Routine"),
    NOTE_EXPECTATION("Note Expectation"),
    LIVE_LOCATION("Live Location"),
    OBSERVE_SCREEN("Observe Screen"),
    TAP_SCREEN("Tap Screen"),
    NAVIGATE("Navigate"),
    NEAREST_PLACE("Nearest Place"),
    DIRECTIONS("Directions"),
    WEATHER("Weather"),
    SMART_HOME("Smart Home"),
    VOICE_SHORTCUT("Voice Shortcut"),
    REPEAT_ACTION("Repeat Last Action"),
    UNDO_ACTION("Undo Last Action"),
    EXPORT_CONVERSATION("Export Conversation");

    companion object {
        /**
         * Every tool name in the codebase is allowlisted here.
         *
         * INVARIANT: every concrete `Tool` subclass under
         * `com.jarvis.assistant.tools` MUST appear as a key — otherwise
         * [ActionPolicyGate] will reject its first-ever invocation as
         * `ActionUnsupported` and the user sees nothing happen.  A guard
         * test in `ActionPolicyAllowlistTest` pins this list against the
         * registered tool set so omissions fail at build time.
         */
        val APPROVED_TOOL_MAP: Map<String, ActionType> = mapOf(
            // Calls + messaging
            "call_contact"        to CALL_CONTACT,
            "end_call"            to END_CALL,
            "send_sms"            to SEND_MESSAGE,
            "whatsapp_message"    to SEND_WHATSAPP,
            "send_email"          to SEND_EMAIL,

            // Media + device controls
            "volume_control"      to ADJUST_VOLUME,
            "media_control"       to CONTROL_MEDIA,
            "music_search"        to MUSIC_SEARCH,
            "flashlight"          to TOGGLE_TORCH,

            // Time-based
            "set_alarm"           to SET_ALARM,
            "set_timer"           to SET_TIMER,
            "location_reminder"   to SET_REMINDER,

            // Memory + briefing
            "memory_recall"       to READ_MEMORY,
            "memory_stats"        to READ_MEMORY,
            "daily_briefing"      to DAILY_BRIEFING,

            // Image + camera + audio
            "ImageGeneration"     to SAVE_APP_IMAGE_OUTPUT,
            "camera_capture"      to CAPTURE_PHOTO,
            "analyze_camera_view" to ANALYZE_CAMERA_VIEW,
            "audio_recording"     to AUDIO_RECORDING,

            // Apps + web
            "open_app"            to OPEN_APP,
            "web_search"          to SEARCH_WEB,
            "weather"             to WEATHER,
            "smart_home"          to SMART_HOME,
            "help"                to HELP,
            "shopping_list"       to SHOPPING_LIST,

            // Calendar + notifications
            "calendar"            to READ_CALENDAR,
            "read_notifications"  to READ_NOTIFICATIONS,
            "clear_notifications" to CLEAR_NOTIFICATIONS,

            // Maps + location
            "where_am_i"          to LIVE_LOCATION,
            "nearest_place"       to NEAREST_PLACE,
            "directions"          to DIRECTIONS,
            "navigate"            to NAVIGATE,

            // Screen vision + actuation
            "look_at_this"        to OBSERVE_SCREEN,
            "read_screen"         to OBSERVE_SCREEN,
            "tap_screen"          to TAP_SCREEN,

            // Routines
            "save_routine"        to SAVE_ROUTINE,
            "run_routine"         to RUN_ROUTINE,
            "list_routines"       to LIST_ROUTINES,
            "delete_routine"      to DELETE_ROUTINE,

            // Reference / housekeeping
            "voice_shortcut"      to VOICE_SHORTCUT,
            "mute_suggestion"     to MUTE_SUGGESTION,
            "note_expectation"    to NOTE_EXPECTATION,
            "repeat_last_action"  to REPEAT_ACTION,
            "undo_last_action"    to UNDO_ACTION,
            "export_conversation" to EXPORT_CONVERSATION
        )

        fun fromToolName(toolName: String): ActionType? = APPROVED_TOOL_MAP[toolName]
    }
}
