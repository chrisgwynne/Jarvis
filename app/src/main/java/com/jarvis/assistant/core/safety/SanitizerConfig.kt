package com.jarvis.assistant.core.safety

/**
 * SanitizerConfig — which classes of PII the [Sanitizer] should redact
 * from strings before they leave the device (LLM context, remote logs,
 * crash reports).
 *
 * Defaults are conservative: every category on. Callers who need raw text
 * (tool bodies that actually dial a number or send a message) opt out
 * per-call by passing [Sanitizer.Scope.RAW].
 */
data class SanitizerConfig(
    val redactPhoneNumbers: Boolean = true,
    val redactEmails: Boolean = true,
    val redactStreetAddresses: Boolean = true,
    val redactCreditCards: Boolean = true,
    val redactLongDigitRuns: Boolean = true,
    val redactUrls: Boolean = false,
) {
    companion object {
        val STRICT = SanitizerConfig()
        val OFF = SanitizerConfig(
            redactPhoneNumbers = false,
            redactEmails = false,
            redactStreetAddresses = false,
            redactCreditCards = false,
            redactLongDigitRuns = false,
            redactUrls = false,
        )
    }
}
