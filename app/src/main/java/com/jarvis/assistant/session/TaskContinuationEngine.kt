package com.jarvis.assistant.session

import android.util.Log
import com.jarvis.assistant.session.context.ContextBundle

private const val TAG = "TaskContinuationEngine"

/**
 * Resolves continuation phrases like "turn it off", "move that to Friday",
 * "reply yes", and "mark it done" using the active [ContextBundle].
 *
 * This engine runs after [FollowUpCoordinator] (which handles fully-specified
 * multi-turn tool flows) and before [ToolRegistry] (which handles single-shot
 * commands).  It bridges the gap: short, context-dependent follow-ups that
 * ToolRegistry would miss and that FollowUpCoordinator doesn't track.
 *
 * Each [resolve] call returns a [ContinuationResult]:
 *  - [ContinuationResult.Execute] — enough context; dispatch this tool + params
 *  - [ContinuationResult.AskSlot] — partial context; ask user one question
 *  - [ContinuationResult.Respond] — local response without tool dispatch
 *  - [ContinuationResult.Cancel]  — user cancelled; say "OK."
 *  - [ContinuationResult.PassThrough] — not a continuation phrase; fall through
 */
class TaskContinuationEngine(
    private val sessionStateEngine: SessionStateEngine,
    private val contextBundle: ContextBundle,
) {

    sealed class ContinuationResult {
        /** Enough info to execute — dispatch this tool with these params. */
        data class Execute(val toolName: String, val params: Map<String, String>) : ContinuationResult()
        /** Need one more piece of info — speak prompt and wait. */
        data class AskSlot(val prompt: String, val slotName: String) : ContinuationResult()
        /** Speak this and continue listening — no tool needed. */
        data class Respond(val text: String) : ContinuationResult()
        /** User explicitly cancelled. */
        object Cancel : ContinuationResult()
        /** Not a continuation phrase. */
        object PassThrough : ContinuationResult()
    }

    // ── Regex patterns ────────────────────────────────────────────────────────

    private val CANCEL_RE = Regex(
        """^(cancel|never\s+mind|nevermind|forget\s+it|nope|no\s+thanks|stop\s+that|don'?t)\s*\.?\s*$""",
        RegexOption.IGNORE_CASE
    )

    private val HA_TOGGLE_RE = Regex(
        """turn\s+it\s+(off|on)|switch\s+it\s+(off|on)|turn\s+that\s+(off|on)|flip\s+it|toggle\s+it""",
        RegexOption.IGNORE_CASE
    )

    private val HA_OFF_RE   = Regex("""turn\s+(?:it|that)\s+off|switch\s+(?:it|that)\s+off""", RegexOption.IGNORE_CASE)
    private val HA_ON_RE    = Regex("""turn\s+(?:it|that)\s+on|switch\s+(?:it|that)\s+on""",  RegexOption.IGNORE_CASE)

    private val CALENDAR_MOVE_RE = Regex(
        """(?:move|reschedule|change|shift)\s+(?:it|that)\s+to\s+(.+)|(?:move|reschedule)\s+(?:it|that)$""",
        RegexOption.IGNORE_CASE
    )

    private val MESSAGE_REPLY_RE = Regex(
        """(?:reply|respond|text\s+back|message\s+back)(?:\s+(?:with|saying|to\s+say))?\s*:?\s*(.*)""",
        RegexOption.IGNORE_CASE
    )

    private val TODOIST_DONE_RE = Regex(
        """mark\s+(?:it|that)\s+done|complete\s+(?:it|that)|(?:it|that)'?s\s+done|done\s+with\s+(?:it|that)""",
        RegexOption.IGNORE_CASE
    )

    private val TODOIST_URGENT_RE = Regex(
        """make\s+(?:it|that)\s+urgent|mark\s+(?:it|that)\s+urgent|(?:it|that)\s+is\s+urgent""",
        RegexOption.IGNORE_CASE
    )

    // ── Entry point ───────────────────────────────────────────────────────────

    fun resolve(transcript: String): ContinuationResult {
        val t = transcript.trim()

        // Cancellation short-circuits everything
        if (CANCEL_RE.matches(t)) {
            if (sessionStateEngine.activeGoal != null || sessionStateEngine.pendingAction != null) {
                sessionStateEngine.cancelAll()
                Log.d(TAG, "[CONTINUATION_MATCHED] cancel")
                return ContinuationResult.Cancel
            }
        }

        // Home Assistant toggle — context-guarded
        tryHaToggle(t)?.let { return it }

        // Calendar reschedule — context-guarded
        tryCalendarMove(t)?.let { return it }

        // Message reply — context-guarded
        tryMessageReply(t)?.let { return it }

        // Todoist completion / urgency — context-guarded
        tryTodoistAction(t)?.let { return it }

        Log.d(TAG, "[CONTEXT_AMBIGUOUS] no continuation matched for '$t'")
        return ContinuationResult.PassThrough
    }

    // ── Domain handlers ───────────────────────────────────────────────────────

    private fun tryHaToggle(t: String): ContinuationResult? {
        if (!HA_TOGGLE_RE.containsMatchIn(t)) return null
        val ha = contextBundle.homeAssistant?.current ?: return null

        val newAction = when {
            HA_OFF_RE.containsMatchIn(t) -> "off"
            HA_ON_RE.containsMatchIn(t)  -> "on"
            else                          -> ha.oppositeAction  // "flip it" / "toggle it"
        }

        Log.d(TAG, "[CONTINUATION_MATCHED] ha-toggle entity=${ha.entityId} action=$newAction")
        return ContinuationResult.Execute(
            toolName = "smart_home",
            params   = mapOf(
                "action"      to newAction,
                "entity_name" to ha.friendlyName,
                "domain_hint" to ha.domain,
                "value"       to "",
            )
        )
    }

    private fun tryCalendarMove(t: String): ContinuationResult? {
        if (!CALENDAR_MOVE_RE.containsMatchIn(t)) return null
        val event = contextBundle.calendar?.current ?: return null

        val m = CALENDAR_MOVE_RE.find(t)
        val when_ = m?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }

        return if (when_ != null) {
            Log.d(TAG, "[CONTINUATION_MATCHED] calendar-move title=${event.title} when=$when_")
            ContinuationResult.Execute(
                toolName = "calendar",
                params   = mapOf(
                    "action"   to "reschedule",
                    "eventId"  to (event.eventId?.toString() ?: ""),
                    "title"    to event.title,
                    "when"     to when_,
                )
            )
        } else {
            Log.d(TAG, "[CONTINUATION_MATCHED] calendar-move needs 'when'")
            // Set goal so the next utterance fills the "when" slot
            sessionStateEngine.setGoal(
                ConversationGoal(
                    type     = GoalType.EDIT_CALENDAR_EVENT,
                    slots    = listOf(PendingSlot("when", "When do you want to move it to?")),
                    meta     = mutableMapOf(
                        "eventId" to (event.eventId?.toString() ?: ""),
                        "title"   to event.title,
                    ),
                    sourceIntent = t,
                )
            )
            ContinuationResult.AskSlot(
                prompt   = "When do you want to move \"${event.title}\" to?",
                slotName = "when"
            )
        }
    }

    private fun tryMessageReply(t: String): ContinuationResult? {
        if (!MESSAGE_REPLY_RE.containsMatchIn(t)) return null
        val msg = contextBundle.message?.current ?: return null

        val m = MESSAGE_REPLY_RE.find(t)
        val body = m?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }

        return if (body != null) {
            Log.d(TAG, "[CONTINUATION_MATCHED] reply to=${msg.sender} body=$body channel=${msg.channel}")
            val toolName = when (msg.channel) {
                com.jarvis.assistant.session.context.MessageChannel.WHATSAPP -> "whatsapp_send"
                else -> "sms_send"
            }
            ContinuationResult.Execute(
                toolName = toolName,
                params   = mapOf(
                    "contact" to msg.sender,
                    "number"  to (msg.senderNumber ?: ""),
                    "body"    to body,
                )
            )
        } else {
            // "reply" alone — need the body
            sessionStateEngine.setGoal(
                ConversationGoal(
                    type   = GoalType.REPLY_TO_MESSAGE,
                    slots  = listOf(PendingSlot("body", "What should I say?")),
                    meta   = mutableMapOf(
                        "sender"  to msg.sender,
                        "number"  to (msg.senderNumber ?: ""),
                        "channel" to msg.channel.name,
                    ),
                    sourceIntent = t,
                )
            )
            ContinuationResult.AskSlot(prompt = "What should I reply?", slotName = "body")
        }
    }

    private fun tryTodoistAction(t: String): ContinuationResult? {
        val task = contextBundle.todoist?.current ?: return null

        return when {
            TODOIST_DONE_RE.containsMatchIn(t) -> {
                Log.d(TAG, "[CONTINUATION_MATCHED] todoist-done task=${task.taskContent}")
                ContinuationResult.Execute(
                    toolName = "todoist",
                    params   = mapOf("action" to "complete", "task_id" to task.taskId)
                )
            }
            TODOIST_URGENT_RE.containsMatchIn(t) -> {
                Log.d(TAG, "[CONTINUATION_MATCHED] todoist-urgent task=${task.taskContent}")
                ContinuationResult.Execute(
                    toolName = "todoist",
                    params   = mapOf("action" to "update", "task_id" to task.taskId, "priority" to "4")
                )
            }
            else -> null
        }
    }
}
