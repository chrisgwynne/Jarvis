package com.jarvis.assistant.proactive

import android.content.Context
import android.util.Log
import com.jarvis.assistant.audio.TtsEngine
import com.jarvis.assistant.service.JarvisService
import com.jarvis.assistant.util.JarvisNotificationHelper

/**
 * TtsProactiveDispatcher — the production [ProactiveDispatcher] implementation.
 *
 * ## Dispatch behaviour
 *
 * | Action type      | Voice enabled | Behaviour                                     |
 * |------------------|---------------|-----------------------------------------------|
 * | SpeakAction      | true          | Calls [TtsEngine.speak] with the event text   |
 * | SpeakAction      | false         | Posts a notification via [JarvisNotificationHelper] |
 * | PassiveAction    | —             | Posts a notification via [JarvisNotificationHelper] |
 * | NoAction         | —             | No-op (should not reach dispatch)             |
 *
 * @param context              Application or service context for posting notifications.
 * @param ttsEngine            TTS engine used for spoken delivery.
 * @param onPassiveAction      Optional hook invoked for every [ProactiveAction.PassiveAction]
 *                             (useful for testing or updating in-app UI state).
 * @param voiceResponseEnabled Lambda that returns the current voice-response setting; consulted
 *                             on every dispatch so runtime setting changes are respected.
 */
class TtsProactiveDispatcher(
    private val context: Context,
    private val ttsEngine: TtsEngine,
    private val onPassiveAction: (ProactiveAction.PassiveAction) -> Unit = {},
    private val voiceResponseEnabled: () -> Boolean = { true }
) : ProactiveDispatcher {

    companion object {
        private const val TAG = "TtsProactiveDispatcher"
    }

    override suspend fun dispatch(action: ProactiveAction) {
        when (action) {
            is ProactiveAction.SpeakAction -> {
                Log.d(TAG, "SpeakAction: \"${action.text}\"")
                if (voiceResponseEnabled()) {
                    // Suppress wake detection while speaking so Jarvis doesn't
                    // hear its own audio and re-trigger the pipeline.
                    JarvisService.suppressWake(context)
                    try {
                        ttsEngine.speak(action.text)
                    } finally {
                        JarvisService.restoreWake(context)
                    }
                } else {
                    // Voice off — fall back to a notification so the message is not lost
                    Log.d(TAG, "Voice disabled — posting notification for SpeakAction")
                    JarvisNotificationHelper.postProactiveAlert(
                        context = context,
                        title   = "Jarvis",
                        body    = action.text
                    )
                }
            }

            is ProactiveAction.PassiveAction -> {
                Log.d(TAG, "PassiveAction: title=\"${action.title}\" body=\"${action.body}\"")
                // Invoke the optional hook (e.g. update UI state, logging in tests)
                onPassiveAction(action)
                // Post the real notification so the user actually sees the alert
                JarvisNotificationHelper.postProactiveAlert(
                    context = context,
                    title   = action.title,
                    body    = action.body ?: action.title
                )
            }

            is ProactiveAction.NoAction -> {
                // Nothing to do — should not normally reach the dispatcher
                Log.v(TAG, "NoAction received (${action.reason}) — ignoring")
            }
        }
    }
}
