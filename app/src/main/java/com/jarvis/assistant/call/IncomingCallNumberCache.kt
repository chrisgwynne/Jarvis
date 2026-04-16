package com.jarvis.assistant.call

/**
 * Process-level cache bridging [com.jarvis.assistant.calls.JarvisCallScreeningService]
 * and [com.jarvis.assistant.call.integration.ContactsPhoneLookupResolver] on Android 12+ (API 31).
 *
 * On API 31+ TelephonyCallback no longer delivers the incoming number (privacy change).
 * CallScreeningService still receives the full handle — this cache lets it write the
 * number so the resolver can read it when the RINGING event fires (~1 s later).
 *
 * Entries are consumed on first poll to prevent stale matches.  TTL of 30 s handles
 * any realistic gap between screenCall() and CALL_STATE_RINGING.
 */
object IncomingCallNumberCache {

    private const val TTL_MS = 30_000L

    @Volatile private var cachedNumber: String? = null
    @Volatile private var timestamp: Long = 0L

    /** Called by JarvisCallScreeningService when a call is first offered. */
    fun put(incomingNumber: String) {
        cachedNumber = incomingNumber
        timestamp    = System.currentTimeMillis()
    }

    /**
     * Returns the cached number if present and within TTL, then clears it.
     * Returns null if the cache is empty or expired.
     */
    fun poll(): String? {
        val n = cachedNumber ?: return null
        if (System.currentTimeMillis() - timestamp > TTL_MS) {
            cachedNumber = null
            return null
        }
        cachedNumber = null
        return n
    }
}
