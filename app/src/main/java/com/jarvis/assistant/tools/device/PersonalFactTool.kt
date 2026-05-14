package com.jarvis.assistant.tools.device

import android.util.Log
import com.jarvis.assistant.memory.ProfileMemoryService
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * PersonalFactTool — answers direct identity questions ("what's my name",
 * "where do I live", "how old am I") by reading the user-profile facts
 * stored in [ProfileMemoryService].  Never hits the LLM.
 *
 * # Why this exists
 *
 * Without this tool, "what's my name" had no matcher and fell through to
 * the local LLM.  The LLM is given the user's profile facts as context,
 * but it could (and did) bleed other facts — e.g. "I also know I love
 * you" — when answering identity questions.  Reading the fact directly
 * is faster, deterministic, and never reveals adjacent facts.
 *
 * # Matched phrases
 *
 *   "what's my name"           → user.name
 *   "what is my name"
 *   "who am i"
 *   "do you know my name"
 *   "remind me of my name"
 *
 *   "where do i live"          → user.location
 *   "what's my address"
 *
 *   "where am i from"          → user.hometown
 *
 *   "how old am i"             → user.age
 *   "what's my age"
 *
 *   "what's my job"            → user.job (or user.occupation/profession)
 *   "what do i do for work"
 *
 *   "when's my birthday"       → user.birthday
 *
 * All matchers are case-insensitive.  When the fact is missing, the tool
 * says so clearly so the user can teach it ("My name is …").  We never
 * fabricate.
 */
class PersonalFactTool(
    private val profile: ProfileMemoryService
) : Tool {

    companion object { private const val TAG = "PersonalFactTool" }

    private enum class Kind(
        val factKeys: List<String>,
        val notKnownPrompt: String,
        val answer: (String) -> String,
    ) {
        NAME(
            listOf("user.name"),
            "I don't know your name yet. Tell me and I'll remember.",
            { "Your name is $it." },
        ),
        LOCATION(
            listOf("user.location"),
            "I don't know where you live yet.",
            { "You live in $it." },
        ),
        HOMETOWN(
            listOf("user.hometown"),
            "I don't know where you're from yet.",
            { "You're from $it." },
        ),
        AGE(
            listOf("user.age"),
            "I don't know your age yet.",
            { "You're $it." },
        ),
        JOB(
            listOf("user.job", "user.occupation", "user.profession"),
            "I don't know what you do yet.",
            { "You're $it." },
        ),
        BIRTHDAY(
            listOf("user.birthday"),
            "I don't know your birthday yet.",
            { "Your birthday is $it." },
        ),
        NATIONALITY(
            listOf("user.nationality"),
            "I don't know your nationality yet.",
            { "You're $it." },
        ),
    }

    override val name        = "personal_fact"
    override val description = "Recall a fact the user has previously told Jarvis about themselves."
    override val riskClass   = com.jarvis.assistant.tools.framework.RiskClass.LOW

    override fun schema() = ToolSchema(
        name        = name,
        description = "Answer a direct identity question (name, age, location, " +
            "hometown, job, birthday, nationality) by reading the stored " +
            "user profile.  Never use this for facts about anyone else.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to emptyMap<String, Any>(),
            "required" to emptyList<String>()
        )
    )

    private val NAME_RX = Regex(
        """(?:what(?:'?s|\s+is)\s+(?:my\s+name|i'?m\s+called)|who\s+am\s+i|do\s+you\s+(?:know|remember)\s+my\s+name|remind\s+me\s+of\s+my\s+name|tell\s+me\s+my\s+name)""",
        RegexOption.IGNORE_CASE
    )
    private val LOCATION_RX = Regex(
        """(?:where\s+do\s+i\s+live|what(?:'?s|\s+is)\s+my\s+(?:address|location))""",
        RegexOption.IGNORE_CASE
    )
    private val HOMETOWN_RX = Regex(
        """(?:where\s+am\s+i\s+from|where(?:'?s|\s+is)\s+(?:my\s+)?hometown)""",
        RegexOption.IGNORE_CASE
    )
    private val AGE_RX = Regex(
        """(?:how\s+old\s+am\s+i|what(?:'?s|\s+is)\s+my\s+age)""",
        RegexOption.IGNORE_CASE
    )
    private val JOB_RX = Regex(
        """(?:what(?:'?s|\s+is)\s+my\s+(?:job|profession|occupation|career)|what\s+do\s+i\s+do(?:\s+for\s+(?:a\s+)?(?:job|work|living))?)""",
        RegexOption.IGNORE_CASE
    )
    private val BIRTHDAY_RX = Regex(
        """(?:when(?:'?s|\s+is)\s+my\s+birthday|what(?:'?s|\s+is)\s+my\s+birthday)""",
        RegexOption.IGNORE_CASE
    )
    private val NATIONALITY_RX = Regex(
        """what(?:'?s|\s+is)\s+my\s+nationality""",
        RegexOption.IGNORE_CASE
    )

    override fun matches(transcript: String): ToolInput? {
        val kind = when {
            NAME_RX.containsMatchIn(transcript)        -> Kind.NAME
            LOCATION_RX.containsMatchIn(transcript)    -> Kind.LOCATION
            HOMETOWN_RX.containsMatchIn(transcript)    -> Kind.HOMETOWN
            AGE_RX.containsMatchIn(transcript)         -> Kind.AGE
            JOB_RX.containsMatchIn(transcript)         -> Kind.JOB
            BIRTHDAY_RX.containsMatchIn(transcript)    -> Kind.BIRTHDAY
            NATIONALITY_RX.containsMatchIn(transcript) -> Kind.NATIONALITY
            else                                       -> return null
        }
        Log.d(TAG, "[PERSONAL_FACT_MATCH] kind=$kind \"$transcript\"")
        return ToolInput(transcript, mapOf("kind" to kind.name))
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val kindName = input.param("kind").ifBlank { return ToolResult.Failure("Sorry?") }
        val kind = runCatching { Kind.valueOf(kindName) }.getOrNull()
            ?: return ToolResult.Failure("Sorry?")
        // Read in order of declared key precedence so e.g. job → occupation → profession.
        var value: String? = null
        for (key in kind.factKeys) {
            value = profile.getFact(key)
            if (!value.isNullOrBlank()) break
        }
        Log.d(TAG, "[PERSONAL_FACT_READ] kind=$kind value=${if (value.isNullOrBlank()) "<missing>" else "<present>"}")
        return if (value.isNullOrBlank()) {
            ToolResult.Success(kind.notKnownPrompt)
        } else {
            ToolResult.Success(kind.answer(value))
        }
    }
}
