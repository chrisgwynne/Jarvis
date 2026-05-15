package com.jarvis.assistant.session

import android.util.Log

private const val TAG = "PendingSlotResolver"

/**
 * Intercepts short utterances and fills the next pending slot in the active
 * [ConversationGoal] or [PendingAction].
 *
 * Examples:
 *
 *   Goal: CREATE_TODOIST_TASK, pending slot "time"
 *   User: "8pm"  →  fills slot, returns Ready
 *
 *   Goal: REPLY_TO_MESSAGE, pending slot "body"
 *   User: "I'll be there in 10"  →  fills slot, returns Ready
 *
 *   Goal: EDIT_CALENDAR_EVENT, pending slot "when"
 *   User: "Friday at 10"  →  fills slot, returns Ready
 *
 * Does NOT try to parse the value — the calling coordinator passes the raw
 * transcript as the slot value and lets the receiving tool interpret it.
 *
 * Returns [SlotResult]:
 *  - [SlotResult.FilledAndReady] — all slots filled, caller should execute
 *  - [SlotResult.FilledNeedsMore] — slot filled but more remain
 *  - [SlotResult.Cancelled] — user said cancel while slot was pending
 *  - [SlotResult.PassThrough] — no active slot; fall through
 */
class PendingSlotResolver(private val sessionStateEngine: SessionStateEngine) {

    sealed class SlotResult {
        /** All required slots are now filled — execute the goal. */
        data class FilledAndReady(val goal: ConversationGoal) : SlotResult()
        /** Slot filled but more remain — ask the next prompt. */
        data class FilledNeedsMore(val nextPrompt: String) : SlotResult()
        /** User cancelled mid-fill. */
        object Cancelled : SlotResult()
        /** No pending slot; don't intercept this utterance. */
        object PassThrough : SlotResult()
    }

    private val CANCEL_RE = Regex(
        """^(cancel|never\s+mind|nevermind|forget\s+it|stop|no\s+thanks)\s*\.?\s*$""",
        RegexOption.IGNORE_CASE
    )

    fun resolve(transcript: String): SlotResult {
        val session = sessionStateEngine.current ?: return SlotResult.PassThrough
        val goal    = session.activeGoal ?: return SlotResult.PassThrough

        // Only intercept when we're actively waiting for a slot
        if (goal.status != GoalStatus.AWAITING_SLOT &&
            goal.nextUnfilledSlot == null) return SlotResult.PassThrough

        // Cancellation
        if (CANCEL_RE.matches(transcript.trim())) {
            sessionStateEngine.cancelGoal()
            Log.d(TAG, "[SESSION_GOAL_CANCELLED] user cancelled mid-slot")
            return SlotResult.Cancelled
        }

        // Fill the next slot with whatever the user said
        val slot = goal.nextUnfilledSlot ?: return SlotResult.PassThrough
        val value = transcript.trim()

        goal.fillNext(value)
        Log.d(TAG, "[SESSION_SLOT_FILLED] slot=${slot.name} value=$value")

        return if (goal.allSlotsFilled) {
            goal.status = GoalStatus.READY_TO_EXECUTE
            Log.d(TAG, "[SESSION_GOAL_READY] type=${goal.type}")
            SlotResult.FilledAndReady(goal)
        } else {
            val next = goal.nextUnfilledSlot!!
            goal.status = GoalStatus.AWAITING_SLOT
            sessionStateEngine.extendAwaitingSlot()
            Log.d(TAG, "[SESSION_PENDING_SLOT] next=${next.name}")
            SlotResult.FilledNeedsMore(next.prompt)
        }
    }
}
