package com.jarvis.assistant.trust

import com.jarvis.assistant.tools.framework.ToolInput

/**
 * Four-level risk classification for any Jarvis action.
 *
 * More granular than [com.jarvis.assistant.tools.framework.RiskClass] (which has
 * only LOW/MEDIUM/HIGH) and action-aware: smart_home lock/unlock is HIGH_RISK
 * while smart_home light on/off is LOW_RISK.
 *
 * Used exclusively by [AutonomyEngine]; does not replace RiskClass on Tool.
 */
enum class ActionRiskCategory {
    /**
     * Safe, cheap, reversible. Execute immediately with no prompt.
     * Examples: volume, media, torch, open app, navigation, lights, weather,
     *           timers, camera, screenshots, reading calendar/notifications/messages.
     */
    LOW_RISK,

    /**
     * Has side-effects visible to others, but user can usually recover.
     * Auto-execute when trust is high; otherwise ask a short confirmation.
     * Examples: sending messages, calling contacts, sharing photos,
     *           deleting Todoist tasks, modifying HA scenes.
     */
    MEDIUM_RISK,

    /**
     * Irreversible or sensitive. Always confirm before execution.
     * Examples: deleting calendar events, smart lock control, purchasing,
     *           account changes, deleting large histories.
     */
    HIGH_RISK,

    /**
     * Catastrophic / irreversible at the system level. Refuse and direct the
     * user to do it manually.
     * Examples: factory reset, wipe data, security credential changes.
     */
    CRITICAL;

    companion object {

        /**
         * Classify a tool by name.  When [input] is provided, action-specific
         * overrides are applied (e.g. smart_home lock → HIGH_RISK).
         */
        fun classify(toolName: String, input: ToolInput? = null): ActionRiskCategory {
            return when (toolName) {

                // ── Always LOW ────────────────────────────────────────────────
                "flashlight",
                "volume_control", "volume",
                "media_control", "music_search",
                "set_timer", "timer",
                "time", "battery",
                "calculator", "unit_conversion", "stopwatch",
                "brightness", "dnd", "screen_rotation",
                "settings_panel", "find_phone",
                "weather",
                "where_am_i",
                "open_app", "close_app",
                "maps_navigation_followup",
                "camera_capture", "selfie_capture",
                "look_at_this", "analyze_camera_view",
                "ocr_scan", "read_screen",
                "screenshot",
                "read_sms", "read_notifications",
                "daily_briefing",
                "calendar",         // read-only queries
                "memory_recall",
                "help",
                "time_tool"         -> LOW_RISK

                // ── Smart home: action-aware ───────────────────────────────────
                "smart_home"        -> when (input?.param("action")) {
                    "lock", "unlock" -> HIGH_RISK
                    "scene", "script" -> MEDIUM_RISK
                    else              -> LOW_RISK   // lights, switches, fan, climate
                }

                // ── Navigation (LOW — fast and reversible) ────────────────────
                "directions", "navigate", "nearest_place" -> LOW_RISK

                // ── Messages / calls ──────────────────────────────────────────
                "send_sms", "sms_send", "sms",
                "whatsapp_message", "whatsapp_send", "whatsapp",
                "email_send", "email"   -> MEDIUM_RISK

                "call_contact", "call"  -> MEDIUM_RISK

                // ── Media share ────────────────────────────────────────────────
                "share_media",
                "view_media",
                "visual_followup"       -> MEDIUM_RISK

                // ── Set alarm (off = MEDIUM; set = LOW) ───────────────────────
                "set_alarm", "alarm"    -> when (input?.param("action")) {
                    "cancel", "delete", "disable" -> MEDIUM_RISK
                    else                           -> LOW_RISK
                }

                // ── Todoist: action-aware ─────────────────────────────────────
                "todoist"               -> when (input?.param("action")) {
                    "delete", "cancel", "complete" -> MEDIUM_RISK
                    else                            -> LOW_RISK
                }

                // ── Calendar: action-aware ────────────────────────────────────
                "calendar_create"       -> MEDIUM_RISK

                // ── Shopping list ─────────────────────────────────────────────
                "shopping_list"         -> LOW_RISK

                // ── Reminders ─────────────────────────────────────────────────
                "set_reminder", "create_reminder",
                "location_reminder"     -> LOW_RISK
                "cancel_reminder",
                "list_reminders"        -> LOW_RISK

                // ── Routines ──────────────────────────────────────────────────
                "save_routine"          -> MEDIUM_RISK
                "run_routine"           -> LOW_RISK
                "delete_routine"        -> HIGH_RISK
                "list_routines"         -> LOW_RISK

                // ── Audio recording ───────────────────────────────────────────
                "audio_recording"       -> LOW_RISK

                // ── Image generation ──────────────────────────────────────────
                "image_generation"      -> LOW_RISK

                // ── Contacts / phone ─────────────────────────────────────────
                "end_call"              -> LOW_RISK
                "reply_notification"    -> MEDIUM_RISK
                "clipboard"             -> LOW_RISK
                "screen_tap", "tap_screen" -> LOW_RISK

                // ── Dangerous device actions ──────────────────────────────────
                "clear_notifications"   -> MEDIUM_RISK

                // ── Conversation export ───────────────────────────────────────
                "conversation_export"   -> HIGH_RISK

                // ── Memory / profile ─────────────────────────────────────────
                "memory_stats"          -> LOW_RISK
                "personal_fact"         -> LOW_RISK
                "voice_shortcut"        -> LOW_RISK

                // ── OpenClaw status ───────────────────────────────────────────
                "openclaw_status"       -> LOW_RISK

                // ── Report / system ───────────────────────────────────────────
                "report_issue"          -> LOW_RISK
                "undo_last_action",
                "repeat_last_action",
                "note_expectation",
                "mute_suggestion"       -> LOW_RISK

                // ── CRITICAL: catastrophic / irreversible system actions ───────
                "factory_reset",
                "wipe_data",
                "install_app",
                "uninstall_app",
                "root_device",
                "disable_security"      -> CRITICAL

                // Unknown tools default to LOW (never lock the owner out)
                else                    -> LOW_RISK
            }
        }
    }
}
