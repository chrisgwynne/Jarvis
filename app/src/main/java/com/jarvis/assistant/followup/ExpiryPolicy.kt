package com.jarvis.assistant.followup

/**
 * ExpiryPolicy — maps [FlowType] to an absolute expiry timestamp.
 *
 * Timeouts are intentionally conservative: a user who wakes Jarvis and
 * then says nothing for longer than the timeout is almost certainly no
 * longer thinking about the previous task.
 */
object ExpiryPolicy {

    fun expiresAt(type: FlowType): Long {
        val now = System.currentTimeMillis()
        return now + when (type) {
            FlowType.MESSAGE_DRAFT    -> 5 * 60_000L   // 5 min — composing takes time
            FlowType.EMAIL_DRAFT      -> 5 * 60_000L   // 5 min — composing takes time
            FlowType.CALL_CONTACT     -> 2 * 60_000L   // 2 min — quick clarification
            FlowType.REMINDER_CREATION -> 5 * 60_000L  // 5 min — may need to think
            FlowType.TIMER_CREATION   -> 2 * 60_000L   // 2 min
            FlowType.APP_LAUNCH       -> 60_000L        // 1 min
            FlowType.CLARIFICATION    -> 2 * 60_000L   // 2 min
        }
    }
}
