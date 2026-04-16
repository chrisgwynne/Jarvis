package com.jarvis.assistant.runtime

/**
 * OfflineManager — decides what to do when the device is offline.
 *
 * STRATEGY:
 *   1. Local-only tools (flashlight, volume, alarm, timer, open app) run as normal.
 *   2. Network-required tools (web search) return a polite "offline" message.
 *   3. LLM calls fail → return a canned offline response instead of crashing.
 *   4. Wake-word detection stays active (SpeechRecognizer may use on-device model).
 *
 * RECOVERY:
 *   JarvisRuntime polls isOnline() before each LLM call.  When it returns true
 *   again, the next user utterance will reach the cloud as normal — no explicit
 *   recovery step is needed.
 */
object OfflineManager {

    /** Tools that work without internet. */
    val LOCAL_TOOLS = setOf(
        "flashlight", "volume_control", "set_alarm", "set_timer", "open_app"
    )

    fun isLocalTool(toolName: String): Boolean = toolName in LOCAL_TOOLS

    /**
     * Returns a user-friendly spoken reply when the LLM cannot be reached.
     * Keeps it brief — the user can ask again once online.
     */
    fun offlineLlmFallback(utterance: String): String = when {
        looksLikeQuestion(utterance) ->
            "I'm offline right now and can't answer that. I can still handle phone controls, alarms, and timers."
        else ->
            "I'm offline. I can still control your phone, flashlight, and set alarms."
    }

    /** User-friendly message when a network-required tool is blocked offline. */
    fun offlineToolMessage(toolName: String): String =
        "I'm offline and can't run $toolName right now. I'll be able to do that once the connection is back."

    private fun looksLikeQuestion(s: String): Boolean {
        val l = s.lowercase().trim()
        return l.startsWith("what") || l.startsWith("who")  ||
               l.startsWith("when") || l.startsWith("where") ||
               l.startsWith("how")  || l.startsWith("why")  ||
               l.endsWith("?")
    }
}
