package com.jarvis.assistant.voice.routing

import android.util.Log
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolRegistry

/**
 * InstantCommandRouter — the first gate after a transcript reaches the
 * runtime.  Decides whether the user's utterance is a **local / device
 * command** that must execute immediately, or something that needs the
 * deeper brain (OpenClaw + LLM + memory).
 *
 * ## Core rule
 *
 * Phone & device control is instant and deterministic.  OpenClaw is the
 * deeper brain, not the first stop.  No local command may call OpenClaw,
 * memory retrieval, or the LLM unless this router explicitly returns
 * [InstantRouteResult.NoMatch].
 *
 * ## Allowlist
 *
 * Only the tool names in [INSTANT_TOOL_INTENTS] can short-circuit remote
 * routing.  Tools that need cloud knowledge (web search, weather, plan
 * generation, image generation, screen analysis…) intentionally fall
 * through so OpenClaw / the LLM still pick them up.
 *
 * ## Pipeline
 *
 * ```
 *   raw transcript
 *     → TranscriptNormalizer.normalizeForMatching (lowercase + phrase rewrites)
 *     → ToolRegistry.match(normalised)
 *     → if matched tool is in INSTANT_TOOL_INTENTS → Match(intent, tool, input)
 *     → else NoMatch(reason)
 * ```
 *
 * The router is pure with respect to the registry — it does NOT execute
 * the tool.  The caller (JarvisRuntime) is responsible for dispatch and
 * the spoken response.  This keeps unit tests trivial.
 */
class InstantCommandRouter(
    private val registry: ToolRegistry,
) {

    companion object {
        private const val TAG = "InstantRouter"

        /**
         * Tool name → high-level intent label for logs.  Membership in this
         * map is the allowlist; any tool not present falls through to the
         * deeper brain even if it matched.
         */
        val INSTANT_TOOL_INTENTS: Map<String, String> = mapOf(
            // Information queries answered from the device itself.
            "time"               to "TIME",
            "battery"            to "BATTERY",
            "where_am_i"         to "LOCATION",

            // Communication.
            "call_contact"       to "CALL",
            "whatsapp_message"   to "SEND_MESSAGE",   // channel=WHATSAPP
            "send_sms"           to "SEND_MESSAGE",   // channel=SMS
            "end_call"           to "END_CALL",

            // Device control.
            "open_app"           to "OPEN_APP",
            "set_timer"          to "TIMER",
            "set_alarm"          to "ALARM",
            "flashlight"         to "FLASHLIGHT",
            "volume_control"     to "VOLUME",
            "media_control"      to "MEDIA",
            "camera_capture"     to "CAMERA",

            // Smart home (Home Assistant — lights, scenes, devices).
            "smart_home"         to "HOME_ASSISTANT_DEVICE",

            // Calendar read + create.
            "calendar"           to "CALENDAR",
        )
    }

    /** Verdict types — sealed so consumers must handle both paths. */
    sealed class InstantRouteResult {
        /** The transcript is an instant-route command — execute and STOP. */
        data class Match(
            val intent: String,
            val tool: Tool,
            val input: ToolInput,
            /** The normalised transcript that produced the match. */
            val normalisedTranscript: String,
        ) : InstantRouteResult()

        /** Not a local instant command — caller should escalate. */
        data class NoMatch(val reason: String) : InstantRouteResult()
    }

    /**
     * Route [rawTranscript].  Returns [InstantRouteResult.Match] when an
     * allowlisted tool matches the normalised text; [InstantRouteResult
     * .NoMatch] otherwise.
     *
     * `isOnline` is forwarded to [ToolRegistry.match] so tools that
     * `requiresNetwork && !isLocalFallback` correctly decline when offline
     * — they wouldn't be an "instant" answer anyway.
     */
    fun route(rawTranscript: String, isOnline: Boolean): InstantRouteResult {
        val normalised = TranscriptNormalizer.normalizeForMatching(rawTranscript)
        Log.d(TAG, "[INSTANT_ROUTER_BEGIN] " +
            "raw=\"$rawTranscript\" normalised=\"$normalised\"")

        if (normalised.isBlank()) {
            Log.d(TAG, "[INSTANT_ROUTER_NO_MATCH] reason=blank_after_normalisation")
            return InstantRouteResult.NoMatch("blank_after_normalisation")
        }

        val match = registry.match(normalised, isOnline)
        if (match == null) {
            Log.d(TAG, "[INSTANT_ROUTER_NO_MATCH] reason=no_tool_matched " +
                "transcript=\"$normalised\"")
            return InstantRouteResult.NoMatch("no_tool_matched")
        }

        val (tool, input) = match
        val intent = INSTANT_TOOL_INTENTS[tool.name]
        if (intent == null) {
            // A tool matched, but it's not in the local-instant allowlist
            // (e.g. web_search, weather, image_generation, look_at_this).
            // Fall through to the deeper brain — the caller decides whether
            // to dispatch this tool via the regular routing path or escalate.
            Log.d(TAG, "[INSTANT_ROUTER_NO_MATCH] reason=tool_not_instant " +
                "tool=${tool.name} transcript=\"$normalised\"")
            return InstantRouteResult.NoMatch("tool_not_instant(${tool.name})")
        }

        Log.d(TAG, "[INSTANT_ROUTER_MATCH] intent=$intent tool=${tool.name} " +
            "transcript=\"$normalised\"")
        return InstantRouteResult.Match(intent, tool, input, normalised)
    }
}
