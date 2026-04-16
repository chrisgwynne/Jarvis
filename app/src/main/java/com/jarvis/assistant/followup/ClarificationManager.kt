package com.jarvis.assistant.followup

/**
 * ClarificationManager — decides what to ask next for a given flow state.
 *
 * Follows a minimum-question strategy: only the first missing required slot
 * produces a question.  Optional slots (e.g. PHONE_TYPE on a call) are asked
 * only if relevant.
 *
 * Questions are intentionally concise so they sound natural as voice output.
 */
object ClarificationManager {

    /**
     * Returns the question to ask the user, or null if no question is needed
     * (all required slots are filled).
     */
    fun nextQuestion(flow: ActiveFlow): String? {
        val slot = flow.missingSlots.firstOrNull() ?: return null
        val contact = flow.slot(SlotKey.TARGET_CONTACT)

        return when (flow.type) {

            FlowType.MESSAGE_DRAFT -> when (slot) {
                SlotKey.TARGET_CONTACT  -> "Who do you want to message?"
                SlotKey.MESSAGE_BODY    -> "What do you want to say?"
                SlotKey.MESSAGE_CHANNEL -> "SMS or WhatsApp?"
                else                    -> null
            }

            FlowType.EMAIL_DRAFT -> when (slot) {
                SlotKey.EMAIL_ADDRESS -> "Who's the email address, or say a contact name?"
                SlotKey.EMAIL_SUBJECT -> "What's the subject?"
                SlotKey.MESSAGE_BODY  -> "What do you want to say?"
                else                  -> null
            }

            FlowType.CALL_CONTACT -> when (slot) {
                SlotKey.TARGET_CONTACT -> "Who do you want to call?"
                SlotKey.PHONE_TYPE     -> if (contact != null) "Call $contact on mobile or work?"
                                         else "Mobile or work?"
                else                   -> null
            }

            FlowType.REMINDER_CREATION -> when (slot) {
                SlotKey.REMINDER_CONTENT -> "What should I remind you about?"
                SlotKey.TRIGGER_TIME     -> {
                    val content = flow.slot(SlotKey.REMINDER_CONTENT)
                    if (content != null) "When?" else "When should I remind you?"
                }
                else -> null
            }

            FlowType.TIMER_CREATION -> when (slot) {
                SlotKey.TRIGGER_TIME -> "How long?"
                SlotKey.SUBJECT      -> "What's the timer for?"
                else -> null
            }

            FlowType.APP_LAUNCH -> when (slot) {
                SlotKey.APP_NAME -> "Which app?"
                else -> null
            }

            FlowType.CLARIFICATION -> flow.lastPrompt ?: "Could you be more specific?"
        }
    }

    /**
     * Returns the ordered list of required missing slots for a newly created flow,
     * before any initial extraction has been applied.
     */
    fun initialMissingSlots(type: FlowType): ArrayDeque<SlotKey> = when (type) {
        FlowType.MESSAGE_DRAFT    -> ArrayDeque(listOf(SlotKey.TARGET_CONTACT, SlotKey.MESSAGE_BODY))
        FlowType.EMAIL_DRAFT      -> ArrayDeque(listOf(SlotKey.EMAIL_ADDRESS, SlotKey.EMAIL_SUBJECT, SlotKey.MESSAGE_BODY))
        FlowType.CALL_CONTACT     -> ArrayDeque(listOf(SlotKey.TARGET_CONTACT))
        FlowType.REMINDER_CREATION -> ArrayDeque(listOf(SlotKey.REMINDER_CONTENT, SlotKey.TRIGGER_TIME))
        FlowType.TIMER_CREATION   -> ArrayDeque(listOf(SlotKey.TRIGGER_TIME))
        FlowType.APP_LAUNCH       -> ArrayDeque(listOf(SlotKey.APP_NAME))
        FlowType.CLARIFICATION    -> ArrayDeque()
    }
}
