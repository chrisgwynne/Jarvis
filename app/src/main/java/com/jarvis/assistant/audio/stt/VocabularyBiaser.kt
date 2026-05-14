package com.jarvis.assistant.audio.stt

/**
 * VocabularyBiaser — the single source of truth for "words this app expects
 * to hear", organised so [TranscriptCorrector] can score N-best STT candidates
 * and substitute common mishears with the right token.
 *
 * Static vocabulary lives here as constants.  Dynamic vocabulary (real
 * installed app labels, current HA entities, current contact names) is
 * merged in via [setRuntimeVocab] — JarvisRuntime calls this on startup
 * and whenever the underlying lists refresh.
 *
 * Matching is case-insensitive throughout.  All lookups normalise the
 * input by lower-casing and stripping non-alphanumerics so "WhatsApp",
 * "whats app", "Whats-App" all collide on the same key "whatsapp".
 */
object VocabularyBiaser {

    // ── Static, hard-coded vocabulary ─────────────────────────────────────────

    /** Names the user said this app should always know. */
    val STATIC_CONTACTS: Set<String> = setOf(
        "Mike", "Cath", "Jamie", "Heidi"
    )

    /** Apps that are first-class citizens regardless of installed-app scan. */
    val STATIC_APPS: Set<String> = setOf(
        "WhatsApp", "Spotify", "Calendar", "Camera", "Messages",
        "YouTube", "Gmail", "Maps", "Chrome", "Settings"
    )

    /** Home Assistant rooms and entities the user explicitly cares about. */
    val STATIC_ROOMS: Set<String> = setOf(
        "living room", "dining room", "kitchen", "hallway", "porch",
        "Jamie's room", "Heidi's room", "bathroom", "bedroom", "office"
    )

    val STATIC_DEVICES: Set<String> = setOf(
        "lights", "light", "heating", "fan", "front door",
        "back door", "thermostat", "kettle", "TV"
    )

    /** Project-specific words that STT models love to mangle. */
    val ASSISTANT_TERMS: Set<String> = setOf(
        "Jarvis", "OpenClaw", "Tailscale", "Home Assistant", "Hermes"
    )

    /** Channel words used by [com.jarvis.assistant.tools.device.MessageIntentParser]. */
    val CHANNEL_WORDS: Set<String> = setOf(
        "WhatsApp", "SMS", "message", "text", "email", "call"
    )

    // ── Phonetic mishear corrections ──────────────────────────────────────────

    /**
     * Substring-level corrections applied to **whole transcripts** when the
     * surrounding command context makes the correction safe.  Each entry is
     * `regex (case-insensitive) → replacement`.  Ordered: longer / more
     * specific patterns first.
     *
     * Safety is enforced by [TranscriptCorrector]: corrections are only
     * applied when the rest of the transcript looks like the matching
     * command type (e.g. "cat" → "Cath" only inside a messaging command).
     */
    data class PhoneticRule(
        /** Pattern to match in the transcript (case-insensitive). */
        val pattern: Regex,
        /** Replacement string. */
        val replacement: String,
        /**
         * Context predicate.  Receives the rest of the transcript (lower-cased)
         * and must return true for the rule to fire.  Default: always true.
         */
        val contextOk: (String) -> Boolean = { true }
    )

    private val messagingContext: (String) -> Boolean = { lower ->
        Regex("""\b(send|text|message|whatsapp|wa|call|ring|email)\b""").containsMatchIn(lower)
    }

    private val deviceContext: (String) -> Boolean = { lower ->
        Regex("""\b(turn|switch|set|dim|brighten|open|close|lock|unlock|lights?|heating|fan|door|thermostat)\b""")
            .containsMatchIn(lower)
    }

    val PHONETIC_RULES: List<PhoneticRule> = listOf(
        // WhatsApp mishears — safe everywhere, the word is unambiguous.
        PhoneticRule(Regex("""\bwhat['']?s\s+up\b""", RegexOption.IGNORE_CASE),  "WhatsApp"),
        PhoneticRule(Regex("""\bwhats\s+up\b""",     RegexOption.IGNORE_CASE),  "WhatsApp"),
        PhoneticRule(Regex("""\bwhat\s+app\b""",     RegexOption.IGNORE_CASE),  "WhatsApp"),
        PhoneticRule(Regex("""\bwhats[\s-]?app\b""", RegexOption.IGNORE_CASE),  "WhatsApp"),

        // Project nouns
        PhoneticRule(Regex("""\bopen\s+claw\b""",    RegexOption.IGNORE_CASE),  "OpenClaw"),
        PhoneticRule(Regex("""\btail\s+scale\b""",   RegexOption.IGNORE_CASE),  "Tailscale"),
        PhoneticRule(Regex("""\bhome\s+assistance\b""", RegexOption.IGNORE_CASE), "Home Assistant"),
        PhoneticRule(Regex("""\bjervis\b""",         RegexOption.IGNORE_CASE),  "Jarvis"),
        PhoneticRule(Regex("""\bjarviss\b""",        RegexOption.IGNORE_CASE),  "Jarvis"),

        // Names — only inside messaging/call commands
        PhoneticRule(Regex("""\bcat\b""",            RegexOption.IGNORE_CASE),  "Cath",
            contextOk = messagingContext),
        PhoneticRule(Regex("""\bkath\b""",           RegexOption.IGNORE_CASE),  "Cath",
            contextOk = messagingContext),
        PhoneticRule(Regex("""\bhigh\s+d\b""",       RegexOption.IGNORE_CASE),  "Heidi",
            contextOk = messagingContext),
        PhoneticRule(Regex("""\bhaidi\b""",          RegexOption.IGNORE_CASE),  "Heidi",
            contextOk = messagingContext),
        PhoneticRule(Regex("""\bjaimee?\b""",        RegexOption.IGNORE_CASE),  "Jamie",
            contextOk = messagingContext),
        PhoneticRule(Regex("""\bmic\b""",            RegexOption.IGNORE_CASE),  "Mike",
            contextOk = messagingContext),
        PhoneticRule(Regex("""\bmyk\b""",            RegexOption.IGNORE_CASE),  "Mike",
            contextOk = messagingContext),

        // Smart-home — only inside device commands
        PhoneticRule(Regex("""\blight\s+song\b""",   RegexOption.IGNORE_CASE),  "lights on",
            contextOk = deviceContext),
        PhoneticRule(Regex("""\blights\s+song\b""",  RegexOption.IGNORE_CASE),  "lights on",
            contextOk = deviceContext),
        PhoneticRule(Regex("""\bliving\s+roon\b""",  RegexOption.IGNORE_CASE),  "living room",
            contextOk = deviceContext),
        PhoneticRule(Regex("""\bporsche\b""",        RegexOption.IGNORE_CASE),  "porch",
            contextOk = deviceContext),
    )

    // ── Dynamic vocabulary (refreshed at runtime) ─────────────────────────────

    @Volatile private var runtimeContacts: Set<String> = emptySet()
    @Volatile private var runtimeApps:     Set<String> = emptySet()
    @Volatile private var runtimeRooms:    Set<String> = emptySet()
    @Volatile private var runtimeDevices:  Set<String> = emptySet()

    /**
     * Merge runtime-derived vocabulary into the biaser.  Pass empty sets for
     * categories you don't want to update.  Callers:
     *   - JarvisRuntime calls this at startup with ContactLookup + AppResolver
     *     + HomeAssistantClient results.
     *   - It is safe (and cheap) to call repeatedly when lists refresh.
     */
    fun setRuntimeVocab(
        contacts: Set<String> = runtimeContacts,
        apps:     Set<String> = runtimeApps,
        rooms:    Set<String> = runtimeRooms,
        devices:  Set<String> = runtimeDevices
    ) {
        runtimeContacts = contacts
        runtimeApps     = apps
        runtimeRooms    = rooms
        runtimeDevices  = devices
    }

    // ── Accessors used by TranscriptCorrector ─────────────────────────────────

    fun knownContacts(): Set<String> = STATIC_CONTACTS + runtimeContacts
    fun knownApps():     Set<String> = STATIC_APPS     + runtimeApps
    fun knownRooms():    Set<String> = STATIC_ROOMS    + runtimeRooms
    fun knownDevices():  Set<String> = STATIC_DEVICES  + runtimeDevices

    /** Lower-cased, alphanumeric-only "key" form of a token for set membership. */
    fun key(word: String): String =
        word.lowercase().replace(Regex("[^a-z0-9]"), "")
}
