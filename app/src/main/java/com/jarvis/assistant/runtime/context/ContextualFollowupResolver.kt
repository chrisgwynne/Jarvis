package com.jarvis.assistant.runtime.context

import android.util.Log

/**
 * ContextualFollowupResolver — joins [ContextualFollowupParser] output
 * with the current [RecentActionContextStore] entry to produce a
 * concrete instruction the runtime can execute.
 *
 * Pure / Android-free.  The runtime is the consumer — it reads the
 * resolved [Resolution], runs the corresponding tool, and updates the
 * context store afterwards.  Keeping the orchestration in this class
 * (not in the runtime) means we can unit-test the full join.
 */
object ContextualFollowupResolver {

    private const val TAG = "ContextualResolver"

    sealed class Resolution {
        /**
         * Re-dispatch a tool with overridden parameters.  Caller
         * locates the tool by [toolName] in the registry and calls
         * `dispatch` with the merged input.
         */
        data class Dispatch(
            val toolName: String,
            val params: Map<String, String>,
            val originatingFollowup: ContextualFollowupParser.Followup,
        ) : Resolution()

        /** Speak a short clarification — context exists but can't satisfy. */
        data class Speak(val text: String) : Resolution()

        /** Caller falls through to normal routing. */
        object NotApplicable : Resolution()
    }

    /**
     * Try to resolve [transcript] as a contextual follow-up against
     * the live store.  Returns [Resolution.NotApplicable] for any
     * "doesn't match the parser" or "no recent context" case so the
     * caller's pipeline stays unchanged.
     */
    fun resolve(
        transcript: String,
        store: RecentActionContextStore,
    ): Resolution {
        val followup = ContextualFollowupParser.parse(transcript)
            ?: return Resolution.NotApplicable
        val recent = store.peek()
        if (recent == null) {
            Log.d(TAG, "[CONTEXT_FOLLOWUP_NO_CONTEXT] followup=$followup")
            // Soft-fall-through: no recent action, let downstream routing
            // try.  For bare "turn off" without context that means the
            // normal tool parser will likely also miss it and the LLM
            // will get a generic phrase — acceptable.
            return Resolution.NotApplicable
        }
        Log.d(TAG, "[CONTEXT_FOLLOWUP_MATCH] followup=$followup " +
            "recentTool=${recent.tool} recentType=${recent.type}")

        return when (followup) {
            is ContextualFollowupParser.Followup.RepeatToggle ->
                resolveToggle(followup, recent)

            ContextualFollowupParser.Followup.ShowMedia ->
                resolveShowMedia(recent)

            ContextualFollowupParser.Followup.Repeat ->
                resolveRepeat(recent)

            ContextualFollowupParser.Followup.ShareCurrent ->
                resolveShare(recent)

            ContextualFollowupParser.Followup.Cancel ->
                resolveCancel(recent)
        }
    }

    // ── Per-followup resolvers ────────────────────────────────────────────

    private fun resolveToggle(
        followup: ContextualFollowupParser.Followup.RepeatToggle,
        recent: RecentActionContextStore.Entry,
    ): Resolution {
        // The recent action must be a togglable device — flashlight,
        // smart home device, volume.  Anything else and the bare
        // "turn off" doesn't have an addressable target.
        return when (recent.type) {
            RecentActionContextStore.ActionType.DEVICE_TOGGLE -> {
                val direction = when (followup.direction) {
                    ContextualFollowupParser.Followup.Direction.ON     -> "on"
                    ContextualFollowupParser.Followup.Direction.OFF    -> "off"
                    ContextualFollowupParser.Followup.Direction.TOGGLE -> "toggle"
                }
                // Carry the original target ("flashlight", a specific
                // light name) plus the new direction.  Tools that take
                // `state` instead of `direction` accept both keys.
                Resolution.Dispatch(
                    toolName = recent.tool,
                    params   = recent.params + mapOf(
                        "direction" to direction,
                        "state"     to direction,
                        "action"    to direction,
                        "target"    to (recent.target ?: ""),
                    ).filterValues { it.isNotBlank() },
                    originatingFollowup = followup,
                )
            }
            RecentActionContextStore.ActionType.SMART_HOME -> {
                // Same shape, just routed through smart_home so HA gets
                // the original entity_id (carried in recent.params).
                val direction = when (followup.direction) {
                    ContextualFollowupParser.Followup.Direction.ON     -> "on"
                    ContextualFollowupParser.Followup.Direction.OFF    -> "off"
                    ContextualFollowupParser.Followup.Direction.TOGGLE -> "toggle"
                }
                Resolution.Dispatch(
                    toolName = "smart_home",
                    params   = recent.params + mapOf(
                        "action" to direction,
                        "state"  to direction,
                        "target" to (recent.target ?: ""),
                    ).filterValues { it.isNotBlank() },
                    originatingFollowup = followup,
                )
            }
            RecentActionContextStore.ActionType.VOLUME -> {
                // "turn off" after a volume change → mute.  "turn on"
                // is a no-op-ish — speak gentle clarification.
                if (followup.direction == ContextualFollowupParser.Followup.Direction.OFF) {
                    Resolution.Dispatch(
                        toolName = "volume_control",
                        params   = mapOf("direction" to "mute"),
                        originatingFollowup = followup,
                    )
                } else {
                    Resolution.Speak("Did you mean unmute?")
                }
            }
            else ->
                // No togglable device in context — pass through to
                // normal routing.  Caller continues the pipeline.
                Resolution.NotApplicable
        }
    }

    private fun resolveShowMedia(recent: RecentActionContextStore.Entry): Resolution {
        val uri = recent.mediaUri
        if (uri.isNullOrBlank()) {
            // Most recent action wasn't a media capture — soft-fall.
            return Resolution.NotApplicable
        }
        return Resolution.Dispatch(
            toolName = "view_media",
            params   = mapOf(
                "uri"  to uri,
                "kind" to (recent.params["kind"] ?: "photo"),
            ),
            originatingFollowup = ContextualFollowupParser.Followup.ShowMedia,
        )
    }

    private fun resolveRepeat(recent: RecentActionContextStore.Entry): Resolution {
        // "Do that again" — replay the same tool with the same params.
        return Resolution.Dispatch(
            toolName = recent.tool,
            params   = recent.params,
            originatingFollowup = ContextualFollowupParser.Followup.Repeat,
        )
    }

    private fun resolveShare(recent: RecentActionContextStore.Entry): Resolution {
        val uri = recent.mediaUri
            ?: return Resolution.Speak("There's nothing to share right now.")
        return Resolution.Dispatch(
            toolName = "share_media",
            params   = mapOf("uri" to uri),
            originatingFollowup = ContextualFollowupParser.Followup.ShareCurrent,
        )
    }

    private fun resolveCancel(recent: RecentActionContextStore.Entry): Resolution {
        // Reminders and proactive plans have their own cancel paths;
        // here we just speak a polite acknowledgement and let the
        // store be cleared by the caller.
        return Resolution.Speak("Cancelled.")
    }
}
