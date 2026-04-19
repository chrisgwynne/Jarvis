package com.jarvis.assistant.conversation

/**
 * ConversationClassifier — lightweight intent classifier that runs BEFORE ToolRegistry.
 *
 * Classifies every user utterance into a primary [ConversationIntent] so the routing
 * layer can decide whether any tools are needed, rather than letting every transcript
 * fall through to keyword-based tool matching.
 *
 * Priority order (first match wins):
 *   1. ACTION_REQUEST   — explicit device command (call, open, set alarm, etc.)
 *   2. PERSONAL_UPDATE  — user sharing something about themselves / their life
 *   3. REAL_WORLD_LOOKUP — requires live external data (weather, news, scores, prices)
 *   4. MEMORY_QUERY     — asking what Jarvis knows
 *   5. FOLLOW_UP_REPLY  — short fragment continuing an active exchange
 *   6. CASUAL_CHAT      — everything else; LLM handles naturally
 */
object ConversationClassifier {

    fun classify(input: String): ConversationIntent {
        val lower = input.lowercase().trim()

        // "I wonder if..." / "I was thinking about..." — musing aloud, not a query.
        // Must be checked BEFORE REAL_WORLD_LOOKUP to prevent keyword false-matches.
        if (isMusing(lower)) return ConversationIntent.CASUAL_CHAT

        if (isActionRequest(lower))    return ConversationIntent.ACTION_REQUEST
        if (isPersonalUpdate(lower))   return ConversationIntent.PERSONAL_UPDATE
        if (isRealWorldLookup(lower))  return ConversationIntent.REAL_WORLD_LOOKUP
        if (isMemoryQuery(lower))      return ConversationIntent.MEMORY_QUERY
        if (isFollowUpReply(lower))    return ConversationIntent.FOLLOW_UP_REPLY

        return ConversationIntent.CASUAL_CHAT
    }

    // ── ACTION_REQUEST ──────────────────────────────────────────────────────

    // Explicit command verbs at the start — these clearly request a device action.
    private val ACTION_PREFIXES = listOf(
        "call ", "phone ", "ring ", "dial ",
        "text ", "message ", "send a text", "send a message", "send message",
        "whatsapp ", "facetime ",
        "open ", "launch ", "start ", "close ",
        "set an alarm", "set alarm", "set a timer", "set timer",
        "set a reminder", "set reminder", "remind me",
        "create a reminder", "add a reminder",
        "turn on ", "turn off ", "switch on ", "switch off ",
        "volume up", "volume down", "mute ", "unmute ",
        "flashlight on", "flashlight off", "torch on", "torch off",
        "take a photo", "take a picture", "take a selfie", "take a screenshot",
        "play ", "pause ", "stop ", "skip ", "next track", "previous track",
        "read my notifications", "read notifications",
        "start recording", "stop recording",
        "end call", "hang up", "answer the call"
    )

    private fun isActionRequest(lower: String): Boolean {
        if (ACTION_PREFIXES.any { lower.startsWith(it) }) return true
        // Live-location queries ("where am I", "what's my location", "what
        // street am I on") are ACTION_REQUESTS, not chit-chat.  Without this
        // line the 3-word "where am I" falls through to FOLLOW_UP_REPLY and
        // never reaches WhereAmITool.  Pattern is intentionally broader than
        // the tool's own regex — the classifier only needs to route; the tool
        // itself does the precise matching.
        if (LIVE_LOCATION_QUERY_RE.containsMatchIn(lower)) return true
        return false
    }

    private val LIVE_LOCATION_QUERY_RE = Regex(
        """^(?:where\s+(?:am\s+i|exactly\s+am\s+i)""" +
        """|what(?:'?s|\s+is)\s+my\s+(?:current\s+)?location""" +
        """|my\s+current\s+location""" +
        """|current\s+location""" +
        """|locate\s+me""" +
        """|what\s+(?:street|road|avenue|lane)\s+am\s+i\s+on""" +
        """|what\s+(?:town|city|village|area|country|neighbourhood|neighborhood|suburb)\s+am\s+i\s+in)\b""",
        RegexOption.IGNORE_CASE
    )

    // ── PERSONAL_UPDATE ─────────────────────────────────────────────────────

    // First-person or personal statements — user sharing state, plans, or facts.
    // These must NEVER be routed to web search.
    private val PERSONAL_PREFIXES = listOf(
        "i'm going ", "i am going ", "i'm heading ", "i am heading ",
        "i'm flying ", "i am flying ", "i'm travelling ", "i am travelling ",
        "i'm visiting ", "i am visiting ", "i'm staying ", "i am staying ",
        "i'm meeting ", "i am meeting ", "i'm seeing ", "i am seeing ",
        "i'm tired", "i am tired", "i'm exhausted", "i'm stressed",
        "i'm excited", "i'm happy", "i'm sad", "i'm bored",
        "i'm sick", "i'm ill", "i'm not feeling", "i don't feel", "i feel ",
        "i had ", "i went ", "i saw ", "i met ", "i spoke ", "i talked ",
        "i just ", "i've been ", "i have been ", "i've had ",
        "i start ", "i finish ", "i need to ", "i want to ",
        "i prefer ", "i hate ", "i love ", "i like ", "i don't like ",
        "i usually ", "i always ", "i never ", "i often ",
        "we're going ", "we are going ", "we went ", "we had ", "we're ",
        "my wife ", "my husband ", "my kids ", "my family ", "my friend ",
        "my job ", "my work ", "my boss ", "my colleague ",
        "my day ", "my morning ", "my evening ", "my night ",
        "big day", "long day", "busy day", "tough day", "good day", "bad day",
        "rough day", "hard day", "great day",
        "work was ", "today was ", "it was a ", "it's been a "
    )

    private val PERSONAL_PATTERNS = listOf(
        // "i'm [location/state]": "i'm home", "i'm at work", "i'm back"
        Regex("""^i(?:'m| am) (?:at|on|in|off|back|home|away|out|here|there|done|ready)\b"""),
        // "i've [past action]": "i've finished", "i've decided"
        Regex("""^i(?:'ve| have) (?:just |finally |already )?\w+ed\b"""),
    )

    private fun isPersonalUpdate(lower: String): Boolean {
        if (PERSONAL_PREFIXES.any { lower.startsWith(it) }) return true
        return PERSONAL_PATTERNS.any { it.containsMatchIn(lower) }
    }

    // ── MUSING — thoughts said aloud that are not queries ─────────────────
    // "I wonder if it'll rain today", "I was thinking about calling Mum",
    // "I'm not sure if I should..." — these sound like lookups but are not.
    private val MUSING_PREFIXES = listOf(
        "i wonder ", "i was wondering ", "i was thinking ", "i've been thinking ",
        "i'm not sure if ", "i'm not sure whether ", "i'm thinking about ",
        "i kind of want to ", "i might ", "i might want to ", "i was going to ",
        "maybe i should ", "maybe i'll ", "not sure if i should "
    )

    private fun isMusing(lower: String): Boolean =
        MUSING_PREFIXES.any { lower.startsWith(it) }

    // ── REAL_WORLD_LOOKUP ──────────────────────────────────────────────────

    // Requires live or current external data. Use a two-factor approach:
    // certain keywords are always lookups (weather); others need a question context.
    private val ALWAYS_LOOKUP_KEYWORDS = setOf(
        "weather", "forecast", "temperature outside", "rain today", "rain tomorrow",
        "news", "latest news", "breaking news", "headlines",
        "bitcoin", "crypto", "ethereum", "stock price", "share price",
        "nearby", "near me", "directions to", "how do i get to",
        "search for", "look up", "look this up", "search online", "google ",
        "find out ", "tell me about "
    )

    // These only trigger lookup when paired with an interrogative
    private val CONDITIONAL_LOOKUP_KEYWORDS = setOf(
        "score", "scores", "who won", "who is winning", "who won the",
        "traffic", "commute time", "travel time to"
    )

    private val QUESTION_STARTERS = setOf(
        "what", "who", "when", "where", "how", "is there", "are there",
        "what's", "who's", "what are", "what is", "whats"
    )

    private fun isRealWorldLookup(lower: String): Boolean {
        if (ALWAYS_LOOKUP_KEYWORDS.any { lower.contains(it) }) return true
        if (CONDITIONAL_LOOKUP_KEYWORDS.any { lower.contains(it) } &&
            QUESTION_STARTERS.any { lower.startsWith(it) }) return true
        return false
    }

    // ── MEMORY_QUERY ───────────────────────────────────────────────────────

    private fun isMemoryQuery(lower: String): Boolean =
        lower.contains("what do you know") ||
        lower.contains("what do you remember") ||
        lower.contains("what did i tell you") ||
        lower.contains("what did i say") ||
        lower.contains("do you remember") ||
        lower.contains("have you saved") ||
        lower.contains("what have you saved") ||
        lower.contains("tell me what you know") ||
        lower.contains("what have you remembered")

    // ── FOLLOW_UP_REPLY ────────────────────────────────────────────────────

    // Very short utterances are probably continuations of an active exchange,
    // not new topic requests. Guard with action check so "call Mike" isn't caught.
    private fun isFollowUpReply(lower: String): Boolean =
        lower.split(" ").size <= 3 && !isActionRequest(lower)
}
