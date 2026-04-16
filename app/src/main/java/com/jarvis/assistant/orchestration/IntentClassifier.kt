package com.jarvis.assistant.orchestration

/**
 * IntentClassifier — lightweight heuristic pre-filter that runs BEFORE
 * ToolRegistry and the LLM on every user utterance.
 *
 * Returns a typed [ConversationAction] when it recognises a memory-write,
 * memory-recall, reminder, or timer intent.  Returns [ConversationAction.PassThrough]
 * for everything else, leaving ToolRegistry and the LLM unchanged.
 *
 * Intentionally simple: no ML, no embeddings, no external I/O.
 * False negatives (unrecognised phrasings) fall through to the LLM naturally.
 * False positives are guarded by the specificity of the trigger patterns.
 */
object IntentClassifier {

    fun classify(input: String): ConversationAction {
        val lower = input.lowercase().trim()

        // ── Memory: store facts ─────────────────────────────────────────────

        extractName(lower)?.let { name ->
            return ConversationAction.RememberFact("user.name", name, input)
        }
        extractLocation(lower)?.let { loc ->
            return ConversationAction.RememberFact("user.location", loc, input)
        }
        extractPersonalAttribute(lower)?.let { (key, value) ->
            return ConversationAction.RememberFact(key, value, input)
        }
        if (isExplicitMemoryStore(lower)) {
            val value = extractMemoryValue(lower)
            return ConversationAction.RememberFact(
                key      = "fact.${System.currentTimeMillis()}",
                value    = value,
                rawInput = input
            )
        }

        // ── Memory: recall ──────────────────────────────────────────────────

        if (isMemoryRecall(lower)) return ConversationAction.RecallFact(input)

        // ── Timers (check before reminders — "set a timer" is more specific) ─

        if (isCreateTimer(lower)) return ConversationAction.CreateTimer(input)

        // ── Reminders ───────────────────────────────────────────────────────

        if (isCreateReminder(lower)) return ConversationAction.CreateReminder(input)
        if (isListReminders(lower))  return ConversationAction.ListReminders
        if (isCancelReminder(lower)) return ConversationAction.CancelReminder(input)

        return ConversationAction.PassThrough
    }

    // ── Personal attribute extraction ─────────────────────────────────────

    /**
     * Captures personal attribute statements and returns (key, value).
     *
     * Named keys (overwrite in place):
     *   "my age is 30" / "I'm 30 years old"  → user.age
     *   "my name is X" / "call me X"          → user.name  (handled above)
     *   "I live in X"                          → user.location (handled above)
     *   "my job / occupation / profession is X"→ user.job
     *   "my birthday is X"                     → user.birthday
     *   "my hometown is X"                     → user.hometown
     *   "my nationality is X"                  → user.nationality
     *
     * Catch-all (timestamped key, never overwrites):
     *   "my [anything] is/are [value]"         → fact.<millis>
     *   "I'm allergic to X"                    → fact.<millis>
     *   "I have N [noun]"                      → fact.<millis>
     */
    private fun extractPersonalAttribute(lower: String): Pair<String, String>? {
        // Age: "my age is 30" or "I'm / I am 30 years old"
        AGE_PATTERN.find(lower)?.let { m ->
            val age = (m.groupValues[1].takeIf { it.isNotBlank() }
                    ?: m.groupValues[2]).trim()
            if (age.isNotBlank()) return "user.age" to age
        }

        // Named structural attributes
        NAMED_ATTR_PATTERN.find(lower)?.let { m ->
            val attr  = m.groupValues[1].trim().lowercase()
            val value = m.groupValues[2].trim().trimEnd('.', ',')
            if (value.isNotBlank()) {
                val key = when (attr) {
                    "job", "occupation", "profession" -> "user.job"
                    "birthday"                        -> "user.birthday"
                    "hometown"                        -> "user.hometown"
                    "nationality"                     -> "user.nationality"
                    "email"                           -> "user.email"
                    else                              -> "user.$attr"
                }
                return key to value
            }
        }

        // Catch-all: "my [anything] is/are [value]" — store the full statement
        MY_ANYTHING_PATTERN.find(lower)?.let { m ->
            val full = m.value.trim().trimEnd('.', ',')
            if (full.length > 5) return "fact.${System.currentTimeMillis()}" to full
        }

        // Allergies and similar health/lifestyle facts
        if (ALLERGY_PATTERN.containsMatchIn(lower)) {
            return "fact.${System.currentTimeMillis()}" to lower.trim().trimEnd('.', ',')
        }

        // "I have N [noun]" — "I have two kids", "I have a dog named Rex"
        // Exclude transient states: physical symptoms, meetings, problems, etc.
        if (I_HAVE_PATTERN.containsMatchIn(lower) && !I_HAVE_TRANSIENT.containsMatchIn(lower)) {
            return "fact.${System.currentTimeMillis()}" to lower.trim().trimEnd('.', ',')
        }

        return null
    }

    private val AGE_PATTERN = Regex(
        """^(?:my age is\s+(\d+)|i(?:'m| am)\s+(\d+)\s+years?\s+old)\b""",
        RegexOption.IGNORE_CASE
    )

    private val NAMED_ATTR_PATTERN = Regex(
        """^my\s+(job|occupation|profession|birthday|hometown|nationality|email)\s+is\s+(.+)""",
        RegexOption.IGNORE_CASE
    )

    // "my [anything] is/are [value]" — broad personal attribute catch
    // Excludes very short attributes to avoid noise
    private val MY_ANYTHING_PATTERN = Regex(
        """^my\s+.{2,60}?\s+(?:is|are|was|were)\s+.{2,}""",
        RegexOption.IGNORE_CASE
    )

    private val ALLERGY_PATTERN = Regex(
        """^i(?:'m| am) allergic to\s""",
        RegexOption.IGNORE_CASE
    )

    private val I_HAVE_PATTERN = Regex(
        """^i have (?:a |an |one |two |three |four |five |\d+\s)\w""",
        RegexOption.IGNORE_CASE
    )

    // Transient physical/situational states that are NOT worth storing as long-term facts.
    // "I have a headache" should not become a profile fact.
    private val I_HAVE_TRANSIENT = Regex(
        """^i have (?:a |an )(?:headache|cold|fever|cough|sore|meeting|appointment|call|question|problem|issue|bit of|lot of|bunch of|couple of things)""",
        RegexOption.IGNORE_CASE
    )

    // ── Name extraction ────────────────────────────────────────────────────

    /**
     * EXPLICIT IDENTITY PATTERNS ONLY.
     *
     * Accepted:
     *   "my name is Chris"   "call me Chris"   "set my name to Chris"
     *
     * Deliberately excluded:
     *   "I'm X" / "I am X" — too broad; matches "I'm home", "I'm tired", etc.
     *   Those phrases are state/emotion/location and must never trigger identity capture.
     */
    private val NAME_PATTERN = Regex(
        """(?:my name is|call me|set my name to)\s+([a-z][a-z'\-]+(?:\s+[a-z][a-z'\-]+)?)""",
        RegexOption.IGNORE_CASE
    )

    private fun extractName(lower: String): String? {
        NAME_PATTERN.find(lower)?.let { m ->
            val candidate = m.groupValues[1].trim()
            if (candidate.length in 2..40 && !isStopWord(candidate)) {
                return candidate.titleCase()
            }
        }
        return null
    }

    // ── Location extraction ────────────────────────────────────────────────

    private val LOCATION_PATTERN = Regex(
        """(?:i live in|i(?:'m| am) (?:based|located) in|i(?:'m| am) from)\s+([a-z][a-z\s'\-]+)""",
        RegexOption.IGNORE_CASE
    )

    private fun extractLocation(lower: String): String? {
        LOCATION_PATTERN.find(lower)?.let { m ->
            val candidate = m.groupValues[1].trim().trimEnd(',', '.')
            if (candidate.length in 2..50) return candidate.titleCase()
        }
        return null
    }

    // ── Explicit memory value extraction ──────────────────────────────────

    private val MEMORY_PREFIXES = listOf(
        "please remember that ", "please remember ",
        "remember that ", "remember ",
        "note that ", "note: ", "note this ",
        "don't forget that ", "don't forget ",
        "keep in mind that ", "keep in mind ",
        "make a note that ", "make a note ",
        "make note that ", "make note ",
        "jot this down: ", "jot down that ", "jot down ",
        "save this: ", "store this: ",
        "add to memory: ", "memorise that ", "memorize that ",
        "i want you to remember that ", "i want you to remember ",
        "i need you to remember that ", "i need you to remember ",
        "can you remember that ", "can you remember "
    )

    private fun extractMemoryValue(lower: String): String {
        for (prefix in MEMORY_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return lower.removePrefix(prefix).trim().ifBlank { lower }
            }
        }
        return lower.trim()
    }

    // ── Boolean predicates ─────────────────────────────────────────────────

    private fun isExplicitMemoryStore(lower: String): Boolean {
        if (MEMORY_PREFIXES.any { lower.startsWith(it) }) return true
        return EXPLICIT_STORE_PATTERNS.any { it.containsMatchIn(lower) }
    }

    private val EXPLICIT_STORE_PATTERNS = listOf(
        Regex("""^i (?:prefer|always|never|hate|love|like|don't like|dislike|enjoy|avoid)\s""", RegexOption.IGNORE_CASE),
        Regex("""^i (?:usually|typically|often|rarely|sometimes)\s""",                         RegexOption.IGNORE_CASE),
    )

    private fun isMemoryRecall(lower: String): Boolean =
        lower.contains("what's my name") ||
        lower.contains("what is my name") ||
        lower.contains("do you know my name") ||
        lower.contains("what do you know about me") ||
        lower.contains("what do you remember about me") ||
        lower.contains("tell me what you know about me") ||
        lower.contains("what have you remembered")

    private fun isCreateTimer(lower: String): Boolean =
        (lower.startsWith("set") || lower.startsWith("start") || lower.startsWith("create")) &&
        (lower.contains(" timer") || lower.contains("countdown") || lower.contains("count down"))

    private fun isCreateReminder(lower: String): Boolean =
        lower.startsWith("remind me") ||
        lower.startsWith("set a reminder") ||
        lower.startsWith("set reminder") ||
        lower.startsWith("create a reminder") ||
        lower.startsWith("add a reminder") ||
        (lower.startsWith("reminder") && (lower.contains(" to ") || lower.contains(" at ") || lower.contains(" in ")))

    private fun isListReminders(lower: String): Boolean {
        val hasListWord  = lower.contains("what") || lower.contains("show") ||
                           lower.contains("list")  || lower.contains("do i have")
        val hasItemWord  = lower.contains("reminder") || lower.contains("timer") ||
                           lower.contains("alarm")    || lower.contains("scheduled")
        return hasListWord && hasItemWord
    }

    private fun isCancelReminder(lower: String): Boolean {
        val hasCancelWord = lower.contains("cancel") || lower.contains("remove") ||
                            lower.contains("delete")  || lower.contains("clear")
        val hasItemWord   = lower.contains("reminder") || lower.contains("timer") ||
                            lower.contains("alarm")
        return hasCancelWord && hasItemWord
    }

    // ── Utilities ──────────────────────────────────────────────────────────

    private val STOP_WORDS = setOf(
        "a", "an", "the", "it", "is", "am", "was", "are", "not", "no",
        "yes", "ok", "okay", "just", "really", "very"
    )
    private fun isStopWord(word: String) = word.lowercase() in STOP_WORDS

    private fun String.titleCase(): String =
        split(" ").joinToString(" ") { w ->
            w.replaceFirstChar { it.uppercaseChar() }
        }
}
