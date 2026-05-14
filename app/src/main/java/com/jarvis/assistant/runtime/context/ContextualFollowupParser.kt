package com.jarvis.assistant.runtime.context

/**
 * ContextualFollowupParser — recognises bare follow-up commands that
 * only make sense against the most recent action and produces a
 * [Followup] describing what to do.
 *
 * Pure / Android-free.  Returns null when the utterance isn't a
 * recognisable contextual follow-up so the caller falls through to
 * the normal pipeline.
 *
 * **Examples** (assuming the most recent action carried the right type):
 *
 *   prior: flashlight on
 *   user:  "turn off"            → Followup.RepeatToggle(target=OFF)
 *   user:  "turn it off"         → same
 *
 *   prior: take a selfie
 *   user:  "show me the selfie"  → Followup.ShowMedia
 *   user:  "show it"             → Followup.ShowMedia
 *
 *   prior: navigate to Tesco
 *   user:  "share that"          → Followup.ShareCurrent
 *
 *   user:  "do it again"          → Followup.Repeat
 *   user:  "cancel that"          → Followup.Cancel
 */
object ContextualFollowupParser {

    sealed class Followup {
        /** Toggle on/off a device — resolves against last DEVICE_TOGGLE. */
        data class RepeatToggle(val direction: Direction) : Followup()
        enum class Direction { ON, OFF, TOGGLE }

        /** Show last captured media via the gallery / viewer. */
        object ShowMedia : Followup()

        /** Re-do the last action verbatim. */
        object Repeat : Followup()

        /** Cancel / undo the last action — caller decides semantics. */
        object Cancel : Followup()

        /** Share whatever the last action produced (media URI). */
        object ShareCurrent : Followup()
    }

    // ── Patterns ──────────────────────────────────────────────────────────

    /** "turn off" / "turn it off" / "switch off" — bare toggle. */
    private val TOGGLE_OFF_RX = Regex(
        """(?ix)
        ^\s*(?:please\s+)?
        (?:turn|switch)\s+(?:it\s+|that\s+|this\s+)?off
        \s*[.!?]?\s*$
        """,
    )

    private val TOGGLE_ON_RX = Regex(
        """(?ix)
        ^\s*(?:please\s+)?
        (?:turn|switch)\s+(?:it\s+|that\s+|this\s+)?on
        \s*[.!?]?\s*$
        """,
    )

    private val TOGGLE_BARE_RX = Regex(
        """(?ix)
        ^\s*(?:please\s+)?
        toggle\s+(?:it|that|this)
        \s*[.!?]?\s*$
        """,
    )

    /**
     * Show-media patterns.  Accepts:
     *
     *   - "show me the selfie" / "open the photo" / "view the video"
     *   - "show it" / "show that" / "open this"
     *   - "show me that" / "show me the picture"
     *   - "show me" / "open it" — BARE forms.  When there's a recent
     *     media capture context, "show me" is unambiguous.  The
     *     resolver decides whether to satisfy it.
     */
    private val SHOW_MEDIA_RX = Regex(
        """(?ix)
        ^\s*(?:please\s+)?
        (?:show|open|view|see|let\s+me\s+see)
        (?:
            # "show me [optional noun]"
            \s+me
            (?:
                \s+(?:the\s+|that\s+|this\s+|my\s+|it|that|this)
                  (?:\s*(?:selfie|photo|picture|video|recording|screenshot|image))?
              | \s+(?:selfie|photo|picture|video|recording|screenshot|image)
            )?
          |
            # "show <pronoun-or-noun>" (no "me")
            \s+(?:
                (?:the\s+|that\s+|this\s+|my\s+|it|that|this)
                  (?:\s*(?:selfie|photo|picture|video|recording|screenshot|image))?
              | (?:selfie|photo|picture|video|recording|screenshot|image)
            )
        )
        (?:\s+(?:i\s+)?just\s+(?:took|made|captured))?
        \s*[.!?]?\s*$
        """,
    )

    /** "do it again", "do that again", "repeat that", "again". */
    private val REPEAT_RX = Regex(
        """(?ix)
        ^\s*(?:please\s+)?
        (?:
            do\s+(?:it|that)\s+again
          | repeat\s+(?:it|that)
          | again
          | once\s+more
        )
        \s*[.!?]?\s*$
        """,
    )

    /** "share that", "share it", "send it to <name>". */
    private val SHARE_RX = Regex(
        """(?ix)
        ^\s*(?:please\s+)?
        share\s+(?:it|that|this)
        \s*[.!?]?\s*$
        """,
    )

    private val CANCEL_RX = Regex(
        """(?ix)
        ^\s*(?:please\s+)?
        (?:cancel|undo|forget|drop)\s+(?:that|it|this)
        \s*[.!?]?\s*$
        """,
    )

    /**
     * Cheap predicate — true when [transcript] looks like ANY
     * contextual follow-up the parser can resolve.  Used by the runtime
     * to short-circuit when the recent-action slot is empty (no point
     * paying for full parse).
     */
    fun looksLikeFollowup(transcript: String): Boolean {
        val t = transcript.trim()
        if (t.isBlank()) return false
        return TOGGLE_OFF_RX.matches(t) ||
            TOGGLE_ON_RX.matches(t) ||
            TOGGLE_BARE_RX.matches(t) ||
            SHOW_MEDIA_RX.matches(t) ||
            REPEAT_RX.matches(t) ||
            SHARE_RX.matches(t) ||
            CANCEL_RX.matches(t)
    }

    /** Parse [transcript] into a [Followup].  Returns null on no-match. */
    fun parse(transcript: String): Followup? {
        val t = transcript.trim()
        if (t.isBlank()) return null
        return when {
            TOGGLE_OFF_RX.matches(t)   -> Followup.RepeatToggle(Followup.Direction.OFF)
            TOGGLE_ON_RX.matches(t)    -> Followup.RepeatToggle(Followup.Direction.ON)
            TOGGLE_BARE_RX.matches(t)  -> Followup.RepeatToggle(Followup.Direction.TOGGLE)
            SHOW_MEDIA_RX.matches(t)   -> Followup.ShowMedia
            REPEAT_RX.matches(t)       -> Followup.Repeat
            SHARE_RX.matches(t)        -> Followup.ShareCurrent
            CANCEL_RX.matches(t)       -> Followup.Cancel
            else                       -> null
        }
    }
}
