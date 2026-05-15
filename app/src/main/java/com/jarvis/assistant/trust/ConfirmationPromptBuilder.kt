package com.jarvis.assistant.trust

import com.jarvis.assistant.tools.framework.ToolInput

/**
 * Builds short, contextual confirmation prompts.
 *
 * Replaces [com.jarvis.assistant.core.safety.ConfirmationGate]'s generic
 * "Just to confirm — X. Yes or no?" with prompts that sound natural and are
 * specific to what the user actually asked.
 *
 * Design rules:
 *  - 2–5 words maximum
 *  - End with "?" — spoken as a rising intonation
 *  - No "just to confirm" or "are you sure" — too robotic
 *  - Use the contact name / entity name when available
 */
object ConfirmationPromptBuilder {

    fun build(toolName: String, input: ToolInput): String {
        return when (toolName) {

            // ── Messaging ────────────────────────────────────────────────────
            "send_sms", "sms_send", "sms" -> {
                val contact = input.param("contact").ifBlank { input.param("to") }
                if (contact.isNotBlank()) "Text $contact?" else "Send it?"
            }
            "whatsapp_message", "whatsapp_send", "whatsapp" -> {
                val contact = input.param("contact").ifBlank { input.param("to") }
                if (contact.isNotBlank()) "WhatsApp $contact?" else "Send it?"
            }
            "email_send", "email" -> {
                val contact = input.param("to").ifBlank { input.param("contact") }
                if (contact.isNotBlank()) "Email $contact?" else "Send it?"
            }
            "reply_notification" -> "Reply to that?"

            // ── Calls ─────────────────────────────────────────────────────────
            "call_contact", "call" -> {
                val contact = input.param("contact").ifBlank { input.param("name") }
                if (contact.isNotBlank()) "Call $contact?" else "Make the call?"
            }

            // ── Smart home (MEDIUM/HIGH overrides) ───────────────────────────
            "smart_home" -> {
                val entity = input.param("entity_name")
                val action = input.param("action")
                when (action) {
                    "lock"   -> if (entity.isNotBlank()) "Lock $entity?" else "Lock it?"
                    "unlock" -> if (entity.isNotBlank()) "Unlock $entity?" else "Unlock it?"
                    "scene"  -> if (entity.isNotBlank()) "Run $entity scene?" else "Run the scene?"
                    "script" -> if (entity.isNotBlank()) "Run $entity?" else "Run the script?"
                    else     -> "Go ahead?"
                }
            }

            // ── Media sharing ─────────────────────────────────────────────────
            "share_media", "visual_followup" -> "Share this photo?"

            // ── Calendar ──────────────────────────────────────────────────────
            "calendar_create" -> "Add to calendar?"

            // ── Todoist ───────────────────────────────────────────────────────
            "todoist" -> when (input.param("action")) {
                "delete", "cancel" -> "Delete that task?"
                "complete"         -> "Mark it done?"
                else               -> "Go ahead?"
            }

            // ── Alarms ───────────────────────────────────────────────────────
            "set_alarm", "alarm" -> when (input.param("action")) {
                "cancel", "delete", "disable" -> "Cancel that alarm?"
                else                           -> "Set the alarm?"
            }

            // ── Notifications ─────────────────────────────────────────────────
            "clear_notifications" -> "Clear all notifications?"

            // ── Routines ──────────────────────────────────────────────────────
            "save_routine" -> {
                val name = input.param("name").ifBlank { input.param("routine_name") }
                if (name.isNotBlank()) "Save routine \"$name\"?" else "Save that routine?"
            }
            "delete_routine" -> {
                val name = input.param("name").ifBlank { input.param("routine_name") }
                if (name.isNotBlank()) "Delete \"$name\"?" else "Delete that routine?"
            }

            // ── Exports ───────────────────────────────────────────────────────
            "conversation_export" -> "Export this conversation?"

            // ── Generic fallback ──────────────────────────────────────────────
            else -> "Go ahead?"
        }
    }
}
