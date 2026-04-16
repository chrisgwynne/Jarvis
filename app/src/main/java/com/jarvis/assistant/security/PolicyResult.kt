package com.jarvis.assistant.security

sealed class PolicyResult {
    abstract val requestedActionType: ActionType?
    abstract val rawRequestedAction: String
    abstract val timestamp: Long

    /**
     * The action passed all policy checks. It is safe to execute.
     */
    data class ActionApproved(
        override val requestedActionType: ActionType,
        override val rawRequestedAction: String,
        val toolName: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : PolicyResult()

    /**
     * The action type is known but execution was denied.
     * [humanMessage] is suitable for speaking aloud to the user.
     */
    data class ActionDenied(
        override val requestedActionType: ActionType?,
        override val rawRequestedAction: String,
        val reasonCode: DenialReason,
        val humanMessage: String,
        val debugDetails: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : PolicyResult()

    /**
     * The requested action has no mapping in the executor allowlist.
     * It was not silently dropped — it is captured and reported here.
     * [humanMessage] explains to the user why nothing happened.
     */
    data class ActionUnsupported(
        override val requestedActionType: ActionType? = null,
        override val rawRequestedAction: String,
        val toolNameAttempted: String,
        val humanMessage: String = "I understood the request, but this action is not supported by the executor.",
        val debugDetails: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : PolicyResult()

    /**
     * The action request could not be parsed or was structurally invalid.
     * For example: blank transcript, missing required fields.
     */
    data class ActionMalformed(
        override val requestedActionType: ActionType? = null,
        override val rawRequestedAction: String,
        val reasonCode: String,
        val humanMessage: String = "The request was malformed and could not be executed.",
        val debugDetails: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : PolicyResult()

    /**
     * The action was identified as potentially unsafe and was blocked.
     * This is distinct from Denied (which is a policy decision)
     * and Unsupported (which is an allowlist miss).
     */
    data class ActionUnsafe(
        override val requestedActionType: ActionType? = null,
        override val rawRequestedAction: String,
        val reasonCode: String,
        val humanMessage: String = "I understood the request, but it was blocked by the safety policy.",
        val debugDetails: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : PolicyResult()
}
