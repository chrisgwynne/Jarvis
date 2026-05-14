package com.jarvis.assistant.tools.device

/**
 * PhoneNumberNormalizer — converts user / contact numbers into E.164.
 *
 * UK rules:
 *   07xxx xxxxxx → +447xxx xxxxxx
 *   44xxx        → +44xxx
 *   +44xxx       → unchanged
 *   +<other>     → unchanged (assumed already international)
 *
 * For everything else the digits are returned as-is; we never invent a
 * country code we aren't sure about.
 */
object PhoneNumberNormalizer {

    /**
     * Returns the number in E.164 form (`+44…`) when we can be confident,
     * otherwise the cleaned digits.  Never returns an empty string for a
     * non-empty input.
     */
    fun toE164(raw: String, defaultCountry: String = "GB"): String {
        val cleaned = raw.replace(Regex("[^\\d+]"), "")
        if (cleaned.isEmpty()) return raw
        if (cleaned.startsWith("+")) return cleaned

        if (defaultCountry == "GB") {
            return when {
                cleaned.startsWith("07")  -> "+44" + cleaned.substring(1)
                cleaned.startsWith("447") -> "+$cleaned"
                cleaned.startsWith("44")  -> "+$cleaned"
                else                      -> cleaned
            }
        }
        return cleaned
    }

    /**
     * WhatsApp deep links want the international number **without** the
     * leading `+` — e.g. `447123456789`.
     */
    fun toWhatsAppFormat(raw: String): String {
        val e164 = toE164(raw)
        return if (e164.startsWith("+")) e164.substring(1) else e164
    }
}
