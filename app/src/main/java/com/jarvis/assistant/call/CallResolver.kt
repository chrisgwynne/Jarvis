package com.jarvis.assistant.call

/**
 * Result of a contact lookup for an incoming phone number.
 */
data class ResolvedContact(
    /** Best available display name, never null or blank. */
    val displayName: String,
    /** True if this name came from the device contacts database. */
    val isKnown: Boolean,
    /** How confident we are in [displayName]. */
    val confidence: ResolutionConfidence
)

/**
 * CallResolver — maps an incoming phone number to a human-readable name.
 *
 * Production implementation: [ContactsPhoneLookupResolver]
 *
 * Contract:
 *  • Never throws — always returns a [ResolvedContact] (degrades to "Unknown caller").
 *  • [resolve] is a suspend function so implementations can query a content
 *    provider or network resource without blocking the Main thread.
 */
interface CallResolver {

    /**
     * Resolve [number] to a [ResolvedContact].
     *
     * @param number  Raw E.164 or local format number from telephony.
     *                Null on Android 12+ (API 31) where the number is not
     *                provided in the TelephonyCallback.
     */
    suspend fun resolve(number: String?): ResolvedContact
}
