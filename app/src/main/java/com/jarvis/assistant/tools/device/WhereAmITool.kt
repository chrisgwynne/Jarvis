package com.jarvis.assistant.tools.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.jarvis.assistant.location.CurrentLocationProvider
import com.jarvis.assistant.location.LocationResult
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * WhereAmITool — the dedicated LIVE_LOCATION intent handler.
 *
 * "Where am I?" / "What's my location?" / "What street am I on?" always map
 * here, never to memory, saved-place labels, or semantic lookups.  This tool
 * is registered first in [com.jarvis.assistant.tools.framework.ToolRegistry]
 * so nothing else can intercept those phrases.
 *
 * Contract (non-negotiable):
 *
 *   1. Request a fresh, high-accuracy device fix (GPS + fused).
 *   2. Accept cached only if ≤ [FRESH_AGE_MAX_MS] old AND accuracy
 *      ≤ [ACCURACY_MAX_M] — otherwise refresh and try again.
 *   3. Reverse geocode into street / town / postcode.
 *   4. Speak a natural sentence ("You're on High Street in Wrexham.").
 *   5. On any failure, say GPS couldn't be retrieved and *label* any
 *      fallback explicitly ("Your last known location was near …").
 *
 * Saved places ("home", "work") are NEVER returned for these queries.
 */
class WhereAmITool(
    private val context: Context,
    private val locationProvider: CurrentLocationProvider
) : Tool {

    override val name = "where_am_i"
    override val description = "Report the device's current GPS location as a spoken place name"
    override val requiresNetwork = false      // geocoder is on-device when available
    override val isLocalFallback  = true      // always available — the user is asking for raw sensor state
    override val requiredPermissions = listOf(Manifest.permission.ACCESS_COARSE_LOCATION)

    companion object {
        private const val TAG = "WhereAmITool"
        /** Max age of a cached fix we'll trust without re-asking the system. */
        private const val FRESH_AGE_MAX_MS = 30_000L
        /** Max horizontal accuracy (in metres) we'll trust for a spoken reply. */
        private const val ACCURACY_MAX_M  = 100f

        /**
         * Precise, enumerated patterns only.  Anything fuzzier ("where to?",
         * "find me a …") must not land here — we'd rather miss a query than
         * intercept one that belongs to another tool.
         *
         * The patterns cover the specified variations:
         *   where am i / where am i right now / where exactly am i
         *   what('s/is) my location / my current location / current location
         *   what street am i on / what road am i on / what town am i in
         *   what city am i in / what area am i in / locate me
         */
        // Apostrophe-optional contractions: STT often drops them ("whats",
        // "wheres", "wheres at").  Written out with (?:'?s|\s+is) so both
        // forms match without blowing up into more alternations.
        private val LIVE_LOCATION_RE = Regex(
            """(?ix)
            ^\s*
            (?:
                where\s+(?:am\s+i|exactly\s+am\s+i|am\s+i\s+(?:right\s+now|currently|now))
              | what(?:'?s|\s+is)\s+my\s+(?:current\s+)?location
              | my\s+current\s+location
              | current\s+location
              | locate\s+me
              | what\s+(?:street|road|avenue|lane)\s+am\s+i\s+on
              | what\s+(?:town|city|village|area|neighbourhood|neighborhood|suburb)\s+am\s+i\s+in
              | what\s+country\s+am\s+i\s+in
              | tell\s+me\s+(?:my\s+(?:location|current\s+location)|where\s+i\s+am)
            )
            \s*\.?\s*\??\s*$
            """
        )
    }

    /** Which specific flavour of live-location question the user asked. */
    private enum class Granularity { FULL, STREET_ONLY, TOWN_ONLY, COUNTRY_ONLY }

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        if (!LIVE_LOCATION_RE.matches(t)) return null
        val granularity = when {
            t.contains("street", ignoreCase = true) ||
                t.contains("road",   ignoreCase = true) ||
                t.contains("avenue", ignoreCase = true) ||
                t.contains("lane",   ignoreCase = true) -> Granularity.STREET_ONLY
            t.contains("town",  ignoreCase = true) ||
                t.contains("city",       ignoreCase = true) ||
                t.contains("village",    ignoreCase = true) ||
                t.contains("area",       ignoreCase = true) ||
                t.contains("neighbour",  ignoreCase = true) ||
                t.contains("neighbor",   ignoreCase = true) ||
                t.contains("suburb",     ignoreCase = true) -> Granularity.TOWN_ONLY
            t.contains("country", ignoreCase = true) -> Granularity.COUNTRY_ONLY
            else -> Granularity.FULL
        }
        return ToolInput(transcript, mapOf("granularity" to granularity.name))
    }

    override fun schema() = ToolSchema(
        name = name,
        description = "Report the device's current, live GPS-based location. Never substitute a saved/home address."
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        val granularity = runCatching { Granularity.valueOf(input.param("granularity")) }
            .getOrDefault(Granularity.FULL)

        // Precondition checks before touching the provider — so we say the
        // correct reason up front rather than timing out.
        if (!hasCoarseLocationPermission()) {
            return ToolResult.Failure(
                "I couldn't get a live GPS fix — the location permission is off."
            )
        }
        if (!isLocationServicesEnabled()) {
            return ToolResult.Failure(
                "I couldn't get a live GPS fix — location services are switched off."
            )
        }

        // Step 1: re-use the cached fix only if it's both RECENT and ACCURATE.
        var fix = acceptableCached(locationProvider.lastResult)

        // Step 2: otherwise request a fresh high-accuracy fix.
        if (fix == null) {
            try {
                locationProvider.refresh(highAccuracy = true)
            } catch (e: Exception) {
                Log.w(TAG, "refresh threw: ${e.message}")
            }
            fix = acceptableCached(locationProvider.lastResult)
        }

        if (fix == null) {
            // Step 3: live fetch failed — try labelled fallback with the last
            // known result (stale or inaccurate).  Must say so explicitly.
            val fallback = locationProvider.lastResult
            return if (fallback != null) {
                val label = buildSpokenLabel(fallback, granularity) ?: "an unknown area"
                ToolResult.Success("I couldn't get a live GPS fix. Your last known location was near $label.")
            } else {
                ToolResult.Failure("I couldn't get a live GPS fix right now.")
            }
        }

        val spoken = buildSpokenLabel(fix, granularity)
            ?: return ToolResult.Failure(
                "I got a location fix but couldn't name the place."
            )
        return ToolResult.Success(spoken)
    }

    // ── Freshness / accuracy gate ────────────────────────────────────────────

    /**
     * Returns [fix] if it clears both gates (age and accuracy); null otherwise.
     * Null means the caller should ask the provider for a fresh fix.
     */
    private fun acceptableCached(fix: LocationResult?): LocationResult? {
        if (fix == null) return null
        val age = System.currentTimeMillis() - fix.timestampMs
        if (age > FRESH_AGE_MAX_MS) return null
        val acc = fix.accuracyMeters ?: Float.MAX_VALUE
        if (acc > ACCURACY_MAX_M)    return null
        return fix
    }

    // ── Spoken formatting ────────────────────────────────────────────────────

    /**
     * Build the natural spoken sentence for [fix] at the requested [granularity].
     *
     * Returns null only when every candidate name field is missing — caller
     * treats that as "geocode failed" rather than saying nothing.
     */
    private fun buildSpokenLabel(fix: LocationResult, granularity: Granularity): String? {
        val street   = fix.street?.takeIf { it.isNotBlank() }
        val town     = fix.locality?.takeIf { it.isNotBlank() }
        val postcode = fix.postcode?.takeIf { it.isNotBlank() }
        val label    = fix.displayLabel?.takeIf { it.isNotBlank() }

        val approxTail = if (fix.isApproximate) ", but the GPS fix is approximate" else ""

        return when (granularity) {
            Granularity.STREET_ONLY -> when {
                street != null && town != null -> "You're on $street in $town$approxTail."
                street != null                 -> "You're on $street$approxTail."
                town != null                   -> "I can't see the street, but you're in $town."
                else                           -> label?.let { "You're near $it." }
            }
            Granularity.TOWN_ONLY -> when {
                town != null -> "You're in $town$approxTail."
                label != null -> "You're near $label."
                else          -> null
            }
            Granularity.COUNTRY_ONLY -> {
                // countryName isn't stored on LocationResult; fall back to the
                // tail of displayLabel which the geocoder builds in
                // increasing-generality order.
                val country = label?.split(',')?.lastOrNull()?.trim()?.takeIf { it.isNotBlank() }
                country?.let { "You're in $it." } ?: label?.let { "You're near $it." }
            }
            Granularity.FULL -> when {
                street != null && town != null && postcode != null ->
                    "You're on $street in $town, $postcode$approxTail."
                street != null && town != null ->
                    "You're on $street in $town$approxTail."
                street != null ->
                    "You're on $street$approxTail."
                town != null && postcode != null ->
                    "You're in $town, $postcode$approxTail."
                town != null ->
                    "You're in $town$approxTail."
                label != null ->
                    "You're near $label$approxTail."
                else -> null
            }
        }
    }

    // ── System preconditions ─────────────────────────────────────────────────

    private fun hasCoarseLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * Returns true when Android's Location Services master toggle is ON.
     * Either GPS or Network provider being enabled is enough; getCurrentLocation
     * on the fused client will use whichever it can, we just need one.
     */
    private fun isLocationServicesEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return true   // can't check → assume on; the fetch path will fail cleanly
        return try {
            LocationManagerCompat.isLocationEnabled(lm)
        } catch (_: Exception) {
            // Fallback: look at the providers individually.
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }
}
