package com.jarvis.assistant.runtime.context

import android.util.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * RecentActionContextStore — single-slot, short-TTL record of the most
 * recent successful action.  Lets contextual follow-ups resolve
 * pronouns and bare commands against the last thing Jarvis did:
 *
 *   "turn the flashlight on"   → tool=flashlight, params=[on]
 *   "turn off"                 → resolves against the slot → flashlight off
 *
 *   "take a selfie"            → tool=camera_capture, mediaUri=<file>
 *   "show me the selfie"       → resolves to view_media(mediaUri)
 *
 *   "navigate to Tesco"        → tool=navigate, target="Tesco"
 *   "share that"               → resolves to share-current-destination
 *
 * One slot only.  Last-writer-wins.  10-minute default TTL — long
 * enough for natural follow-ups, short enough that a stale slot
 * doesn't bleed into the next session.  Atomic + lock-free so the
 * runtime, proactive engine, and UI can read concurrently.
 *
 * Pure / Android-free.  Clock injection for tests.
 */
class RecentActionContextStore(
    private val ttlMs: Long = 10 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** High-level taxonomy used by the contextual follow-up router. */
    enum class ActionType {
        DEVICE_TOGGLE,      // flashlight, bluetooth, wifi …
        VOLUME,
        MEDIA_PLAYBACK,
        MEDIA_CAPTURE,      // photo/selfie/video
        MEDIA_VIEW,
        OPEN_APP,
        NAVIGATION,
        WEB_SEARCH,
        MESSAGING,
        CALL,
        CALENDAR,
        REMINDER_CREATED,
        SMART_HOME,
        OTHER,
    }

    /** A single recent action.  All fields optional except the basics. */
    data class Entry(
        val type: ActionType,
        val tool: String,
        /** Friendly name for the target ("flashlight", "kitchen lights"). */
        val target: String? = null,
        /** Raw parameters echoed from the dispatch (`direction=on`, etc.). */
        val params: Map<String, String> = emptyMap(),
        /** URI of a captured / displayed media item, when applicable. */
        val mediaUri: String? = null,
        /** Free-form recall string — what the user "got" out of this action. */
        val spokenSummary: String? = null,
        val timestampMs: Long,
        val expiresAtMs: Long,
    )

    private val slot = AtomicReference<Entry?>(null)

    companion object { private const val TAG = "RecentActionContext" }

    /** Record a new action.  Last-writer-wins. */
    fun record(
        type: ActionType,
        tool: String,
        target: String? = null,
        params: Map<String, String> = emptyMap(),
        mediaUri: String? = null,
        spokenSummary: String? = null,
    ) {
        val now = clock()
        val e = Entry(
            type           = type,
            tool           = tool,
            target         = target?.trim()?.takeIf { it.isNotBlank() },
            params         = params,
            mediaUri       = mediaUri?.takeIf { it.isNotBlank() },
            spokenSummary  = spokenSummary?.trim()?.takeIf { it.isNotBlank() },
            timestampMs    = now,
            expiresAtMs    = now + ttlMs,
        )
        slot.set(e)
        Log.d(TAG, "[CONTEXT_STORE_UPDATE] type=$type tool=$tool " +
            "target=$target mediaUri=${mediaUri != null}")
    }

    /** Read the active entry, dropping it if expired. */
    fun peek(): Entry? {
        val cur = slot.get() ?: return null
        if (clock() > cur.expiresAtMs) {
            slot.compareAndSet(cur, null)
            return null
        }
        return cur
    }

    fun clear() { slot.set(null) }
}
