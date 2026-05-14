package com.jarvis.assistant.personality

/**
 * SeriousModeDetector — pure-text classifier.  Returns true when the
 * transcript / event context indicates a serious or vulnerable moment
 * where humour, sarcasm, and playful pushback are unwelcome.
 *
 * Sources signalling SERIOUS:
 *   - emergency keywords (call 999 / police / fire / ambulance)
 *   - medical (chest pain, allergic reaction, overdose)
 *   - safety (intruder, break in, panic)
 *   - legal (lawyer, arrest, custody)
 *   - distress / grief (someone died, I'm scared, I feel awful)
 *   - children's safety (child missing, kid hurt)
 *   - explicit Jarvis-mode request ("serious mode on", "no jokes")
 *
 * Used by [PersonalityPromptSelector] to suppress humour-flavoured
 * sections + drop friendly-roasting tone, and by the proactive
 * dispatcher to keep critical reminders dry.
 *
 * Pure / no Android dependency.
 */
object SeriousModeDetector {

    /**
     * Returns true when [transcript] (or null-safe related context) is
     * classifying as a serious moment.
     *
     * Deliberately permissive — false positives (treating a borderline
     * utterance as serious) are far less harmful than false negatives
     * (joking through an emergency).  When in doubt, return true.
     */
    fun isSerious(
        transcript: String?,
        /** Hint from the runtime: e.g. "failed_command_with_consequence". */
        contextHint: String? = null,
    ): Boolean {
        if (contextHint != null && CONTEXT_HINTS_SERIOUS.any { it.equals(contextHint, ignoreCase = true) }) {
            return true
        }
        val t = transcript?.lowercase()?.trim().orEmpty()
        if (t.isBlank()) return false
        return SERIOUS_RX.any { it.containsMatchIn(t) }
    }

    /** Context hints from the runtime that *force* serious mode regardless of text. */
    private val CONTEXT_HINTS_SERIOUS = setOf(
        "emergency",
        "safety_alert",
        "intruder_detected",
        "smoke_alarm",
        "fall_detected",
        "panic_button",
        "failed_command_with_consequence",
        "distress_detected",
    )

    private val SERIOUS_RX: List<Regex> = listOf(
        // Emergency services + first-responder verbs
        Regex("""\b(?:call\s+)?(?:999|911|112)\b"""),
        Regex("""\b(?:emergency|emergencies|distress|panic)\b"""),
        Regex("""\b(?:police|ambulance|fire\s+brigade|paramedic|firefighter|coast\s+guard)\b"""),
        Regex("""\bcall\s+(?:the\s+)?(?:police|ambulance|fire|coastguard)\b"""),
        // Medical
        Regex("""\b(?:chest\s+pain|can'?t\s+breathe|trouble\s+breathing|allergic\s+reaction|anaphyla(?:xis|ctic)|overdose|seizure|stroke|heart\s+attack|unconscious|bleeding)\b"""),
        // Safety / intrusion
        Regex("""\b(?:intruder|break[\s-]?in|home\s+invasion|prowler|stalker|kidnap|abduct|assault)\b"""),
        // Children's safety
        Regex("""\b(?:child(?:'s|ren'?s?)?\s+(?:hurt|missing|gone|lost|injured)|kid'?s?\s+(?:hurt|missing|injured))\b"""),
        Regex("""\bmy\s+(?:son|daughter|baby)\s+(?:is\s+)?(?:hurt|missing|gone|lost|injured|sick|unconscious)\b"""),
        // Distress / grief / mental health
        Regex("""\b(?:i\s+want\s+to\s+die|suicide|suicidal|kill\s+myself|self[\s-]?harm)\b"""),
        Regex("""\b(?:i'?m\s+(?:scared|terrified|panicking|having\s+a\s+panic\s+attack))\b"""),
        Regex("""\b(?:someone\s+(?:has\s+)?died|just\s+died|she\s+died|he\s+died|they\s+died|my\s+(?:mum|dad|mother|father|wife|husband|partner|brother|sister|friend|son|daughter)\s+(?:died|passed))\b"""),
        // Legal urgency
        Regex("""\b(?:arrested|in\s+custody|need\s+a\s+lawyer|court\s+hearing|restraining\s+order)\b"""),
        // Explicit Jarvis mode requests
        Regex("""\b(?:serious\s+mode|no\s+jokes|stop\s+joking|cut\s+(?:it|the)\s+jokes|be\s+serious)\b"""),
    )
}
