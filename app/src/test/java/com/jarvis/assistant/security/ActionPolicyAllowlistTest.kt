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
     * from a `grep "override val name" tools/**` pass — keep alphabetised
     * so review diffs show additions cleanly.
     */
    private val EXPECTED_TOOL_NAMES = setOf(
        "ImageGeneration",
        "analyze_camera_view",
        "audio_recording",
        "calendar",
        "call_contact",
        "camera_capture",
        "clear_notifications",
        "daily_briefing",
        "delete_routine",
        "directions",
        "end_call",
        "export_conversation",
        "flashlight",
        "help",
        "list_routines",
        "location_reminder",
        "look_at_this",
        "media_control",
        "memory_recall",
        "memory_stats",
        "music_search",
        "mute_suggestion",
        "navigate",
        "nearest_place",
        "note_expectation",
        "open_app",
        "read_notifications",
        "read_screen",
        "repeat_last_action",
        "run_routine",
        "save_routine",
        "send_email",
        "send_sms",
        "set_alarm",
        "set_timer",
        "shopping_list",
        "smart_home",
        "tap_screen",
        "undo_last_action",
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
