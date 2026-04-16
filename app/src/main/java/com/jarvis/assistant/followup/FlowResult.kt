package com.jarvis.assistant.followup

/**
 * FlowResult — the outcome of [FollowUpCoordinator.process] for one user turn.
 *
 * [AwaitingInput] and [Complete] and [Cancelled] are all "handled": JarvisRuntime
 * should speak the message and continue the conversation loop without falling
 * through to IntentClassifier / ToolRegistry / LLM.
 *
 * [PassThrough] means the follow-up system has nothing to contribute for this
 * utterance; the runtime should continue with the normal pipeline.
 */
sealed class FlowResult {

    /** Flow needs another piece of information — speak this question. */
    data class AwaitingInput(val prompt: String) : FlowResult()

    /** Flow executed successfully — speak this confirmation. */
    data class Complete(val response: String) : FlowResult()

    /** User cancelled the active flow — speak this brief acknowledgement. */
    data class Cancelled(val message: String = "Cancelled.") : FlowResult()

    /** Nothing to handle here — pass through to the standard pipeline. */
    object PassThrough : FlowResult()
}
