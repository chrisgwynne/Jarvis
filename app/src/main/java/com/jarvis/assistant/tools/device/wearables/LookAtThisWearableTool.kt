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
 * LookAtThisWearableTool — "look at this" / "what am I looking at" /
 * "take a glasses photo" via the Meta Wearables module.
 *
 * Runs **strictly local-first**:
 *   - If wearables are disabled / SDK absent / glasses unreachable,
 *     returns `ToolResult.NotMatched`-equivalent (null from `matches`)
 *     so the existing `look_at_this` / phone-camera path keeps
 *     working unchanged.
 *   - When wearables ARE ready, captures one photo via
 *     [MetaWearablesManager.capturePhoto] and publishes the URI to
 *     [MediaContextStore] so the existing "show that" / "send that"
 *     follow-ups find the latest capture.
 *
 * **No OpenClaw / Hermes** in the path — the spec is explicit:
 * "glasses commands do not call OpenClaw / Hermes / memory retrieval".
 * Visual analysis (OCR / Q&A) is a separate concern handled by the
 * vision pipeline + the existing `analyze_camera_view` tool.
 *
 * Registered BEFORE the generic `LookAtThisIntentHandler` (screen) and
 * `CameraCaptureTool` so the glasses path wins when the user has the
 * feature enabled and a device is connected.  Decline-on-no-glasses
 * keeps the existing phone-camera flow intact.
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

        /** Trigger phrases.  Kept tight so we don't steal phone-camera
         *  intent when wearables aren't configured. */
        private val TRIGGERS = listOf(
            Regex("""\blook\s+at\s+this\b""", RegexOption.IGNORE_CASE),
            Regex("""\bwhat\s+am\s+i\s+looking\s+at\b""", RegexOption.IGNORE_CASE),
            Regex("""\btake\s+(?:a\s+)?glasses\s+(?:photo|picture|shot)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bcapture\s+this\b""", RegexOption.IGNORE_CASE),
            Regex("""\bglasses?\s+(?:camera|capture)\b""", RegexOption.IGNORE_CASE),
        )
    }

    override fun matches(transcript: String): ToolInput? {
        val s = settings()
        // Decline when wearables are off or the user has explicitly
        // not chosen them for the look-at-this path — the original
        // screen/phone-camera tool handles it.
        if (!s.enabled || !s.useForLookAtThis) return null
        val t = transcript.trim()
        if (TRIGGERS.none { it.containsMatchIn(t) }) return null
        return ToolInput(transcript)
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
