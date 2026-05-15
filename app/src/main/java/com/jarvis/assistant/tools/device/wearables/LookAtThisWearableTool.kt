package com.jarvis.assistant.tools.device.wearables

import android.util.Log
import com.jarvis.assistant.tools.device.media.MediaContextStore
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema
import com.jarvis.assistant.wearables.meta.MetaWearablesManager
import com.jarvis.assistant.wearables.meta.RecentVisualContext
import com.jarvis.assistant.wearables.meta.WearablesSettings

/**
 * Result of [LookAtThisWearableTool.classify] — top-level so unit
 * tests can reference it without instantiating the tool (the tool
 * needs an Android Context-bearing manager which is awkward in JVM
 * tests).  EXPLICIT = trigger names glasses directly; AMBIGUOUS =
 * "look at this" / "capture this" which the existing screen handler
 * also owns.
 */
enum class WearableMatchKind { EXPLICIT, AMBIGUOUS }

/**
 * LookAtThisWearableTool — "look at this" / "what am I looking at" /
 * "take a glasses photo" via the Meta Wearables module.
 *
 * Runs **strictly local-first** and is careful not to break the
 * existing screen-observation `LookAtThisIntentHandler` (which owns
 * "look at this" for phone-screen capture).  The disambiguation
 * rules:
 *
 *   - **Explicit glasses phrases** ("take a glasses photo", "what
 *     am I looking at", "glasses camera") always claim ownership
 *     when the feature is enabled — even when the device isn't
 *     ready, so the user gets a friendly "the glasses aren't
 *     connected" message instead of a silent screenshot.
 *   - **Ambiguous phrases** ("look at this", "capture this") claim
 *     ownership ONLY when the manager state is actually
 *     [MetaWearablesState.isReadyForCapture].  A user with the
 *     feature enabled but glasses powered off / out of range keeps
 *     the existing screenshot fallback transparently.
 *   - When wearables are disabled, the entire tool declines from
 *     `matches()` and the existing path runs unchanged.
 *
 * On a successful capture the URI is published to
 * [MediaContextStore] so the existing "show that" / "send that"
 * follow-ups find the latest glasses photo.
 *
 * **No OpenClaw / Hermes** in the path — the spec is explicit:
 * "glasses commands do not call OpenClaw / Hermes / memory retrieval".
 * Visual analysis (OCR / Q&A) is a separate concern handled by the
 * vision pipeline + the existing `analyze_camera_view` tool.
 *
 * Registered BEFORE the generic `LookAtThisIntentHandler` in
 * [ToolRegistry] so the glasses path can take precedence — but only
 * when the matchers above actually claim the utterance.
 */
class LookAtThisWearableTool(
    private val manager: MetaWearablesManager,
    private val settings: () -> WearablesSettings,
) : Tool {

    override val name = "look_at_this_wearable"
    override val description = "Capture a photo or frame from connected Meta AI glasses."
    override val requiresNetwork = false
    override val isLocalFallback = true
    override val requiredPermissions = emptyList<String>()

    companion object {
        private const val TAG = "LookAtThisWearable"

        /**
         * Phrases that are **unambiguously about the glasses** — they
         * mention the glasses by name or describe a wearable-only
         * action.  These claim ownership immediately whenever the
         * feature is enabled, even if the device isn't ready (the
         * tool will then return a friendly "glasses aren't connected"
         * message instead of silently routing to a screenshot).
         */
        private val EXPLICIT_GLASSES_TRIGGERS = listOf(
            Regex("""\btake\s+(?:a\s+)?glasses\s+(?:photo|picture|shot|video)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bglasses?\s+(?:camera|capture|photo|picture|shot)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bwhat\s+am\s+i\s+looking\s+at\b""", RegexOption.IGNORE_CASE),
        )

        /**
         * Phrases that are **ambiguous** — they could mean the screen
         * OR the glasses depending on context ("look at this" is the
         * canonical case; the existing screen-observation handler
         * also owns it).
         *
         * We claim these ONLY when the wearables device is actually
         * ready to capture, so a user with the feature enabled but
         * the glasses powered off / out of range keeps the existing
         * screenshot fallback.
         */
        private val AMBIGUOUS_TRIGGERS = listOf(
            Regex("""\blook\s+at\s+this\b""", RegexOption.IGNORE_CASE),
            Regex("""\bcapture\s+this\b""", RegexOption.IGNORE_CASE),
        )

        /**
         * Pure-text classifier — testable without a [MetaWearablesManager].
         * Returns the kind of trigger that matched, or null when the
         * transcript should be left alone (so the existing
         * `LookAtThisIntentHandler` / phone-camera path can run).
         */
        @androidx.annotation.VisibleForTesting
        @JvmStatic
        internal fun classify(transcript: String, isGlassesReady: Boolean): WearableMatchKind? {
            val t = transcript.trim()
            if (EXPLICIT_GLASSES_TRIGGERS.any { it.containsMatchIn(t) }) {
                return WearableMatchKind.EXPLICIT
            }
            if (AMBIGUOUS_TRIGGERS.any { it.containsMatchIn(t) } && isGlassesReady) {
                return WearableMatchKind.AMBIGUOUS
            }
            return null
        }
    }

    override fun matches(transcript: String): ToolInput? {
        val s = settings()
        if (!s.enabled || !s.useForLookAtThis) return null
        val kind = classify(transcript, manager.currentState.isReadyForCapture)
            ?: return null
        return ToolInput(transcript, mapOf("trigger" to kind.name.lowercase()))
    }

    override fun schema() = ToolSchema(
        name        = name,
        description = "Capture a single photo from the connected Meta glasses; " +
                      "URI is published to MediaContextStore for follow-ups.",
        parameters  = mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        Log.d(TAG, "[META_CAMERA_STREAM_START] reason=look_at_this_intent")
        val uri = manager.capturePhoto()
        if (uri == null) {
            val state = manager.currentState
            Log.d(TAG, "[META_CAMERA_UNAVAILABLE] state=$state — falling back")
            return ToolResult.Failure(friendlyStateMessage(state))
        }
        // Publish to the shared media context store so "show that" /
        // "send that to Mike" / "remind me about this later" work
        // through the existing ContextualFollowupResolver.
        try {
            MediaContextStore.record(
                MediaContextStore.Entry(
                    filePath = uri,
                    mimeType = "image/jpeg",
                    kind     = "glasses photo",
                )
            )
        } catch (e: Throwable) {
            Log.w(TAG, "MediaContextStore.record threw — continuing", e)
        }
        return ToolResult.Success(
            spokenFeedback = "Got it.",
            rawData        = uri,
        )
    }

    /** Map a [com.jarvis.assistant.wearables.meta.MetaWearablesState] to a
     *  user-safe message.  No raw exception text. */
    private fun friendlyStateMessage(
        state: com.jarvis.assistant.wearables.meta.MetaWearablesState,
    ): String = when (state) {
        com.jarvis.assistant.wearables.meta.MetaWearablesState.DISABLED ->
            "Meta Wearables is off in Settings."
        com.jarvis.assistant.wearables.meta.MetaWearablesState.SDK_UNAVAILABLE ->
            "The glasses module isn't installed on this build."
        com.jarvis.assistant.wearables.meta.MetaWearablesState.NOT_CONFIGURED ->
            "The glasses need configuring in Settings first."
        com.jarvis.assistant.wearables.meta.MetaWearablesState.PERMISSION_MISSING ->
            "I need glasses permission for that."
        com.jarvis.assistant.wearables.meta.MetaWearablesState.DISCONNECTED,
        com.jarvis.assistant.wearables.meta.MetaWearablesState.ERROR ->
            "The glasses aren't connected."
        com.jarvis.assistant.wearables.meta.MetaWearablesState.CONNECTING ->
            "Still connecting to the glasses — try again in a sec."
        com.jarvis.assistant.wearables.meta.MetaWearablesState.CONNECTED,
        com.jarvis.assistant.wearables.meta.MetaWearablesState.CAMERA_READY,
        com.jarvis.assistant.wearables.meta.MetaWearablesState.STREAMING,
        com.jarvis.assistant.wearables.meta.MetaWearablesState.CAPTURING ->
            "That capture didn't work. I've logged it."
    }

    /** Test seam — expose the most recent visual context for assertion. */
    @androidx.annotation.VisibleForTesting
    internal fun peekRecent(): RecentVisualContext? = manager.peekRecentVisualContext()
}
