package com.jarvis.assistant.call.integration

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.jarvis.assistant.call.CallResolver
import com.jarvis.assistant.call.IncomingCallNumberCache
import com.jarvis.assistant.call.ResolutionConfidence
import com.jarvis.assistant.call.ResolvedContact
import com.jarvis.assistant.notifications.JarvisNotificationListener
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * ContactsPhoneLookupResolver — resolves an incoming phone number to a
 * contact display name via [ContactsContract.PhoneLookup].
 *
 * ── LOOKUP STRATEGY ──────────────────────────────────────────────────────
 *
 *   Uses the PhoneLookup content provider which handles number normalisation
 *   (stripping spaces, dashes, country codes) internally — no manual
 *   normalisation required for most locales.
 *
 * ── CACHING ──────────────────────────────────────────────────────────────
 *
 *   Successful and negative lookups are cached in a [ConcurrentHashMap].
 *   Cache is bounded to [CACHE_MAX] entries; it is cleared (not LRU-evicted)
 *   when the limit is reached.  For a voice assistant with low call volume
 *   this is sufficient.
 *
 * ── PERMISSION DEGRADATION ───────────────────────────────────────────────
 *
 *   If READ_CONTACTS is not granted, the resolver returns the formatted
 *   number as the display name (LOW confidence) rather than throwing.
 *
 * ── NULL NUMBER (API 31+) ─────────────────────────────────────────────────
 *
 *   When [number] is null (Android 12+ privacy restriction), the resolver
 *   immediately returns "Unknown caller" (NONE confidence).
 */
class ContactsPhoneLookupResolver(private val context: Context) : CallResolver {

    companion object {
        private const val TAG       = "ContactsPhoneLookup"
        private const val CACHE_MAX = 64
    }

    private val cache = ConcurrentHashMap<String, ResolvedContact>(CACHE_MAX)

    override suspend fun resolve(number: String?): ResolvedContact {
        // On API 31+ TelephonyCallback omits the phone number for privacy.
        // JarvisCallScreeningService writes it to IncomingCallNumberCache when the call
        // is offered — poll that cache here so we can still look up the contact name.
        val effectiveNumber = number?.takeIf { it.isNotBlank() }
            ?: IncomingCallNumberCache.poll()

        if (effectiveNumber.isNullOrBlank()) {
            // Poll the notification listener for up to 2000 ms in 150 ms steps.
            // Incoming call notifications typically post within ~300 ms of
            // CALL_STATE_RINGING, but WhatsApp / carrier dialers can take up to
            // 1.5 s on slower devices.  Peek (not poll) so later resolver calls
            // still see the name — it's cleared explicitly on CallEnded.
            var notifName: String? = JarvisNotificationListener.peekCallerName()
            var waitedMs = 0L
            while (notifName.isNullOrBlank() && waitedMs < 2_000L) {
                delay(150L)
                waitedMs += 150L
                notifName = JarvisNotificationListener.peekCallerName()
            }

            if (!notifName.isNullOrBlank()) {
                val source = JarvisNotificationListener.peekCallerSource()
                Log.d(TAG, "Resolved caller from notification title: \"$notifName\" (src=$source)")
                return ResolvedContact(
                    displayName = notifName,
                    isKnown     = true,
                    confidence  = ResolutionConfidence.LOW
                )
            }
            return ResolvedContact(
                displayName = "Unknown caller",
                isKnown     = false,
                confidence  = ResolutionConfidence.NONE
            )
        }

        cache[effectiveNumber]?.let { return it }

        val result = queryContacts(effectiveNumber)

        if (cache.size >= CACHE_MAX) cache.clear()
        cache[effectiveNumber] = result
        return result
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun queryContacts(number: String): ResolvedContact {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
                PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CONTACTS not granted — using formatted number")
            return ResolvedContact(
                displayName = formatFallback(number),
                isKnown     = false,
                confidence  = ResolutionConfidence.LOW
            )
        }

        val lookupUri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number)
        )

        return try {
            context.contentResolver.query(
                lookupUri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) {
                        Log.d(TAG, "Resolved: \"$number\" → \"$name\"")
                        ResolvedContact(
                            displayName = name,
                            isKnown     = true,
                            confidence  = ResolutionConfidence.HIGH
                        )
                    } else {
                        noMatch(number)
                    }
                } else {
                    noMatch(number)
                }
            } ?: ResolvedContact(
                displayName = "Unknown caller",
                isKnown     = false,
                confidence  = ResolutionConfidence.NONE
            )
        } catch (e: Exception) {
            Log.e(TAG, "Contact lookup failed for \"$number\": ${e.message}")
            ResolvedContact(
                displayName = formatFallback(number),
                isKnown     = false,
                confidence  = ResolutionConfidence.LOW
            )
        }
    }

    private fun noMatch(number: String): ResolvedContact {
        Log.d(TAG, "No contact for: $number")
        return ResolvedContact(
            displayName = formatFallback(number),
            isKnown     = false,
            confidence  = ResolutionConfidence.LOW
        )
    }

    /**
     * Human-readable fallback when no contact name exists.
     * Formats common US/UK patterns; returns the raw number for other formats.
     */
    private fun formatFallback(number: String): String {
        val digits = number.filter { it.isDigit() || it == '+' }
        return when {
            // US 10-digit: (XXX) XXX-XXXX
            digits.length == 10 ->
                "(${digits.substring(0, 3)}) ${digits.substring(3, 6)}-${digits.substring(6)}"
            // US +1XXXXXXXXXX
            digits.length == 11 && (digits.startsWith("1") || digits.startsWith("+1")) -> {
                val d = digits.trimStart('+', '1')
                "(${d.substring(0, 3)}) ${d.substring(3, 6)}-${d.substring(6)}"
            }
            else -> number
        }
    }
}
