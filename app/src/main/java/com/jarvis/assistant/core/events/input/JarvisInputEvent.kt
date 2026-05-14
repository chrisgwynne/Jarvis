package com.jarvis.assistant.core.events.input

/**
 * JarvisInputEvent — the typed contract for "something happened that
 * Jarvis might react to".
 *
 * The point of this hierarchy is to make it impossible at the type level
 * to confuse, for example, a Home Assistant motion alert with a real
 * spoken utterance.  Code paths that act on user input (TTS, transcript
 * append, barge-in, conversation memory) only ever accept
 * [UserVoiceInput] or [TypedUserInput].  Everything else is ambient.
 *
 * Wire-up status (intentional, incremental):
 *  - [com.jarvis.assistant.notifications.JarvisNotificationListener]
 *    classifies every posted notification into one of these types and
 *    uses [HomeAssistantNotification] / [SystemNotification] to flag
 *    "do NOT speak this" downstream.
 *  - Future PRs will migrate the existing EventBus / proactive triggers
 *    to consume this hierarchy directly so the runtime can't silently
 *    treat an ambient event as user input.
 */
sealed class JarvisInputEvent {

    /** Wall-clock ms when the event was observed. */
    abstract val timestampMs: Long

    /**
     * Genuine speech from the microphone — the only event class that
     * may transition the state machine to Listening / Recognising or be
     * appended to the conversation transcript.
     */
    data class UserVoiceInput(
        val transcript: String,
        val confidence: Float,
        val sessionId:  String,
        override val timestampMs: Long
    ) : JarvisInputEvent()

    /** Text typed by the user (chat UI). Treated equivalently to voice. */
    data class TypedUserInput(
        val text:      String,
        val sessionId: String,
        override val timestampMs: Long
    ) : JarvisInputEvent()

    /**
     * A notification posted by the Home Assistant companion app — motion,
     * camera, doorbell, automation status.  Surfaced into Android's
     * notification shade only.  Never spoken, never injected into the
     * transcript, never triggers listening / barge-in.
     */
    data class HomeAssistantNotification(
        val packageName: String,
        val title:       String,
        val text:        String,
        /** Best-effort classification ("motion" | "camera" | "doorbell" | "other"). */
        val kind:        Kind,
        override val timestampMs: Long
    ) : JarvisInputEvent() {
        enum class Kind { MOTION, CAMERA, DOORBELL, AUTOMATION, OTHER }
    }

    /**
     * A notification from any other application.  Eligible for the
     * proactive engine ONLY if the user has not opted out — and even then
     * is only spoken when the engine explicitly decides to surface it.
     */
    data class SystemNotification(
        val packageName: String,
        val title:       String,
        val text:        String,
        override val timestampMs: Long
    ) : JarvisInputEvent()

    /**
     * Internal app events — battery, network change, foreground app, etc.
     * Never spoken on their own; the proactive engine may turn a series
     * of them into a [com.jarvis.assistant.proactive.ProactiveAction].
     */
    data class InternalAppEvent(
        val source: String,
        val payload: Map<String, String>,
        override val timestampMs: Long
    ) : JarvisInputEvent()
}
