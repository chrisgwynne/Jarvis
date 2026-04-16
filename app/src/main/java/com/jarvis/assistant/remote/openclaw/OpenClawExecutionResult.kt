package com.jarvis.assistant.remote.openclaw

/**
 * The outcome of an [OpenClawRouter.execute] call.
 */
sealed class OpenClawExecutionResult {

    /** OpenClaw handled the request and returned a spoken reply. */
    data class Success(
        val spokenSummary: String,
        val fullText:      String
    ) : OpenClawExecutionResult()

    /** Something went wrong — [error] carries a ready-to-speak message. */
    data class Failure(
        val error: OpenClawError
    ) : OpenClawExecutionResult()

    /**
     * OpenClaw was not attempted because the route was classified as LOCAL_FAST,
     * or the settings are disabled.  Caller should fall through to local LLM.
     */
    object Bypassed : OpenClawExecutionResult()
}
