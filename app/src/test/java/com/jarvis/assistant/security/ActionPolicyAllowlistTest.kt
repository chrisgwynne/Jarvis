package com.jarvis.assistant.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ActionPolicyAllowlistTest — guard rail that fails the build the moment
 * a Tool is added without registering its name in
 * [ActionType.APPROVED_TOOL_MAP].  An unmapped tool is silently denied at
 * runtime ("Tool 'foo' has no entry in ActionType.APPROVED_TOOL_MAP"), so
 * we want a noisy unit-test failure long before that happens to a user.
 *
 * The reference list below is the authoritative set of every concrete
 * [com.jarvis.assistant.tools.framework.Tool] under the production source
 * tree.  When you add a new Tool subclass, update this list AND
 * [ActionType.APPROVED_TOOL_MAP] in the same change.
 */
class ActionPolicyAllowlistTest {

    /**
     * Every tool name registered by the production [ToolRegistry].  Sourced
     * from a `grep "override val name" tools` pass — keep alphabetised
     * so review diffs show additions cleanly.
     */
    private val EXPECTED_TOOL_NAMES = setOf(
        "ImageGeneration",
        "analyze_camera_view",
        "app_action",          // AppActionTool — open + search dispatch
        "audio_recording",
        "battery",             // BatteryTool
        "brightness",          // BrightnessTool
        "calculator",          // CalculatorTool
        "calendar",
        "calendar_create",     // CalendarCreateTool
        "call_contact",
        "camera_capture",
        "clear_notifications",
        "clipboard",           // ClipboardTool
        "daily_briefing",
        "delete_routine",
        "directions",
        "dnd",                 // DndTool
        "end_call",
        "export_conversation",
        "find_phone",          // FindPhoneTool
        "flashlight",
        "help",
        "list_routines",
        "location_reminder",
        "look_at_this",
        "look_at_this_wearable",  // LookAtThisWearableTool
        "media_control",
        "memory_recall",
        "memory_stats",
        "music_search",
        "mute_suggestion",
        "navigate",
        "nearest_place",
        "note_expectation",
        "open_app",
        "openclaw_status",     // OpenClawStatusTool
        "personal_fact",       // PersonalFactTool
        "read_notifications",
        "read_screen",
        "read_sms",            // ReadSmsTool
        "recent_calls",        // RecentCallsTool
        "repeat_last_action",
        "reply_notification",  // ReplyNotificationTool
        "report_issue",        // user-triggered bug report tool
        "run_routine",
        "save_routine",
        "screen_rotation",     // ScreenRotationTool
        "screenshot",          // ScreenshotTool
        "send_email",
        "send_sms",
        "set_alarm",
        "set_timer",
        "settings_panel",      // SettingsPanelTool
        "share_location",      // ShareLocationTool
        "share_media",         // ShareMediaTool — Intent.ACTION_SEND chooser
        "shopping_list",
        "smart_home",
        "stopwatch",           // StopwatchTool
        "tap_screen",
        "time",                // TimeTool
        "undo_last_action",
        "unit_conversion",     // UnitConversionTool
        "view_media",          // ViewMediaTool — opens captured media
        "voice_shortcut",
        "volume_control",
        "weather",
        "web_search",
        "whatsapp_message",
        "where_am_i",
    )

    @Test fun `every known Tool name is in APPROVED_TOOL_MAP`() {
        val missing = EXPECTED_TOOL_NAMES - ActionType.APPROVED_TOOL_MAP.keys
        assertTrue(
            "Tools registered but not allowlisted (would deny first invocation): $missing. " +
                "Add an entry in ActionType.APPROVED_TOOL_MAP.",
            missing.isEmpty()
        )
    }

    @Test fun `APPROVED_TOOL_MAP has no orphan entries`() {
        // Catches typos in the map: an entry for "set_remider" would
        // pretend to allow a tool that doesn't exist while the real one
        // gets denied.  Any entry not in the expected set is suspicious.
        val orphans = ActionType.APPROVED_TOOL_MAP.keys - EXPECTED_TOOL_NAMES
        assertTrue(
            "Allowlist contains entries with no matching Tool subclass: $orphans. " +
                "Either rename the tool or remove the stale entry.",
            orphans.isEmpty()
        )
    }

    @Test fun `every approved tool resolves through fromToolName`() {
        for (toolName in ActionType.APPROVED_TOOL_MAP.keys) {
            assertEquals(
                "fromToolName mismatch for '$toolName'",
                ActionType.APPROVED_TOOL_MAP[toolName],
                ActionType.fromToolName(toolName)
            )
        }
    }
}
