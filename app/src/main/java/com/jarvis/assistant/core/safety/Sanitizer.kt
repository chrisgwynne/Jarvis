package com.jarvis.assistant.core.safety

/**
 * Sanitizer — redacts PII from strings before they leave the device.
 *
 * Applied to LLM prompt context, tool result summaries forwarded to the
 * LLM, and any outbound telemetry. Not applied inside tools whose whole
 * purpose is to consume the raw value (dialling, sending, pasting).
 *
 * Redactions are placeholder-based ("[phone]", "[email]", …) rather than
 * destructive so the LLM still gets useful structure ("call [phone]"
 * reads as intent even when the digits are gone). Counts are returned so
 * callers can tell if anything was redacted.
 */
class Sanitizer(
    private val config: SanitizerConfig = SanitizerConfig.STRICT,
) {
    enum class Scope { STRICT, RAW }

    data class Result(val text: String, val redactions: Int)

    fun redact(input: String, scope: Scope = Scope.STRICT): Result {
        if (input.isEmpty() || scope == Scope.RAW) return Result(input, 0)
        var text = input
        var count = 0
        if (config.redactCreditCards) {
            val (t, n) = apply(text, CARD, "[card]")
            text = t; count += n
        }
        if (config.redactEmails) {
            val (t, n) = apply(text, EMAIL, "[email]")
            text = t; count += n
        }
        if (config.redactPhoneNumbers) {
            val (t, n) = apply(text, PHONE, "[phone]")
            text = t; count += n
        }
        if (config.redactStreetAddresses) {
            val (t, n) = apply(text, ADDRESS, "[address]")
            text = t; count += n
        }
        if (config.redactUrls) {
            val (t, n) = apply(text, URL, "[url]")
            text = t; count += n
        }
        if (config.redactLongDigitRuns) {
            val (t, n) = apply(text, LONG_DIGITS, "[number]")
            text = t; count += n
        }
        return Result(text, count)
    }

    fun redactString(input: String, scope: Scope = Scope.STRICT): String =
        redact(input, scope).text

    private fun apply(text: String, regex: Regex, placeholder: String): Pair<String, Int> {
        var count = 0
        val redacted = regex.replace(text) {
            count += 1
            placeholder
        }
        return redacted to count
    }

    companion object {
        private val CARD = Regex("""\b(?:\d[ -]?){13,16}\b""")
        private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
        private val PHONE = Regex("""(?:(?:\+?\d{1,3}[\s\-.]?)?(?:\(?\d{2,4}\)?[\s\-.]?)?\d{3,4}[\s\-.]?\d{3,4})""")
        private val ADDRESS = Regex(
            """\b\d{1,5}\s+[A-Z][a-zA-Z]+(?:\s+[A-Z][a-zA-Z]+)*\s+(?:Street|St|Road|Rd|Avenue|Ave|Lane|Ln|Drive|Dr|Boulevard|Blvd|Way|Court|Ct|Close|Cl|Place|Pl|Terrace|Ter|Square|Sq)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val URL = Regex("""https?://\S+""")
        private val LONG_DIGITS = Regex("""\b\d{9,}\b""")
    }
}
