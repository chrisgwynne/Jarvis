package com.jarvis.assistant.security

enum class ActionType(val displayName: String) {
    SET_REMINDER("Set Reminder"),
    SET_ALARM("Set Alarm"),
    SET_TIMER("Set Timer"),
    OPEN_APP("Open App"),
    CALL_CONTACT("Call Contact"),
    SEND_MESSAGE("Send SMS"),
    SEND_WHATSAPP("Send WhatsApp"),
    TOGGLE_TORCH("Toggle Torch"),
    ADJUST_VOLUME("Adjust Volume"),
    CONTROL_MEDIA("Control Media"),
    READ_MEMORY("Read Memory"),
    SAVE_APP_IMAGE_OUTPUT("Save Generated Image"),
    SEARCH_WEB("Search Web"),
    SHOPPING_LIST("Shopping List"),
    READ_CALENDAR("Read Calendar"),
    READ_NOTIFICATIONS("Read Notifications"),
    DAILY_BRIEFING("Daily Briefing"),
    HELP("Help"),
    CAPTURE_PHOTO("Capture Photo"),
    ANALYZE_CAMERA_VIEW("Analyze Camera View"),
    AUDIO_RECORDING("Audio Recording"),
    MUTE_SUGGESTION("Mute Suggestion");

    companion object {
        /** All tool names that are approved for execution, mapped to their ActionType. */
        val APPROVED_TOOL_MAP: Map<String, ActionType> = mapOf(
            "call_contact"       to CALL_CONTACT,
            "send_sms"           to SEND_MESSAGE,
            "whatsapp_message"   to SEND_WHATSAPP,
            "volume_control"     to ADJUST_VOLUME,
            "media_control"      to CONTROL_MEDIA,
            "flashlight"         to TOGGLE_TORCH,
            "set_alarm"          to SET_ALARM,
            "set_timer"          to SET_TIMER,
            "memory_recall"      to READ_MEMORY,
            "ImageGeneration"    to SAVE_APP_IMAGE_OUTPUT,
            "open_app"           to OPEN_APP,
            "web_search"         to SEARCH_WEB,
            "help"               to HELP,
            "shopping_list"      to SHOPPING_LIST,
            "calendar"           to READ_CALENDAR,
            "read_notifications" to READ_NOTIFICATIONS,
            "daily_briefing"     to DAILY_BRIEFING,
            "create_reminder"     to SET_REMINDER,
            "camera_capture"      to CAPTURE_PHOTO,
            "analyze_camera_view" to ANALYZE_CAMERA_VIEW,
            "audio_recording"     to AUDIO_RECORDING,
            "mute_suggestion"     to MUTE_SUGGESTION
        )

        fun fromToolName(toolName: String): ActionType? = APPROVED_TOOL_MAP[toolName]
    }
}
