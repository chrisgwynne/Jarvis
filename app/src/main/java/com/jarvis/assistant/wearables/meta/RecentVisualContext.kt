package com.jarvis.assistant.wearables.meta

/**
 * RecentVisualContext — a single "I just saw X" record exposed by
 * [WearableContextProvider].
 *
 * Used by:
 *   - `ContextualFollowupResolver` for "show that" / "send that" /
 *     "remind me about this later"
 *   - `MediaContextStore` (the camera-tool sibling) — both stores are
 *     consulted; the more-recent wins
 *   - Settings diagnostics so the user can see what Jarvis last saw
 *
 * Immutable + value-typed.  Pure / no Android dependency.
 */
data class RecentVisualContext(
    val source: Source,
    val mediaType: MediaType,
    /** Local file path or content:// URI.  Format is intentionally
     *  a string so we can move between file:// and content:// without
     *  the data class needing an Android import. */
    val uri: String,
    val timestampMs: Long,
    /** Optional GPS at capture time (lat/lng pair); skipped when off. */
    val location: Pair<Double, Double>? = null,
    /** OCR result, if any.  Blank when none. */
    val recognisedText: String = "",
    /** Coarse object tags from on-device vision, if any. */
    val detectedObjects: List<String> = emptyList(),
    /** One-line summary from the vision analyzer, if any. */
    val summary: String = "",
    /** Wall-clock ms past which the context is considered stale. */
    val expiresAtMs: Long = timestampMs + DEFAULT_TTL_MS,
) {
    enum class Source { META_GLASSES, PHONE_CAMERA, MOCK_WEARABLE }
    enum class MediaType { PHOTO, FRAME, VIDEO }

    /** True iff [nowMs] is past [expiresAtMs]. */
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs >= expiresAtMs

    companion object {
        /** 30 min — long enough for natural follow-ups, short enough not to leak. */
        const val DEFAULT_TTL_MS: Long = 30 * 60_000L
    }
}
