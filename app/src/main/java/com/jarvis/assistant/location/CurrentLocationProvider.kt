package com.jarvis.assistant.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * CurrentLocationProvider — fetches a fresh device location using
 * [com.google.android.gms.location.FusedLocationProviderClient].
 *
 * USAGE:
 *   Call [refresh] once at service start (and optionally periodically).
 *   Read [lastResult] from any thread — it is safe to call from a prompt-build path.
 *
 * WHY FUSED:
 *   FusedLocationProviderClient combines GPS, Wi-Fi, and cell signals to return
 *   the most accurate available fix with minimal battery drain. It also handles the
 *   "cold start" case better than LocationManager.getLastKnownLocation(), which
 *   returns a stale fix (potentially hours old) when the device has not recently
 *   done a location scan.
 *
 * THREAD SAFETY:
 *   [refresh] must be called from a coroutine (it suspends on the Fused callback).
 *   [lastResult] is @Volatile — safe to read from any thread.
 */
class CurrentLocationProvider(private val context: Context) {

    companion object {
        private const val TAG              = "CurrentLocationProvider"
        private const val FRESH_WINDOW_MS  = 5 * 60 * 1_000L   // 5 minutes
        private const val FETCH_TIMEOUT_MS = 10_000L            // 10 s before giving up
    }

    @Volatile var lastResult: LocationResult? = null
        private set

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    /**
     * Fetch a fresh location fix. Suspends until the fix arrives or [FETCH_TIMEOUT_MS]
     * elapses. Falls back to [lastLocation] (cached by Fused) if the active fetch times
     * out. Updates [lastResult] before returning.
     *
     * @param highAccuracy When true, requests PRIORITY_HIGH_ACCURACY (GPS + fused)
     *   rather than the default balanced priority.  Used by the live-location
     *   intent where the user explicitly asked "where am I?" — battery cost is
     *   acceptable because the user is actively waiting for an answer.
     */
    suspend fun refresh(highAccuracy: Boolean = false) {
        if (!hasLocationPermission()) {
            Log.d(TAG, "Location permission not granted — skipping refresh")
            return
        }
        withContext(Dispatchers.IO) {
            val location = fetchCurrentLocation(highAccuracy) ?: fetchLastLocation()
            if (location == null) {
                Log.d(TAG, "No location fix available")
                return@withContext
            }

            val elapsedMs = System.currentTimeMillis() - location.time
            val isFresh   = elapsedMs < FRESH_WINDOW_MS
            val isApprox  = (location.accuracy ?: 0f) > 200f  // >200 m = coarse

            Log.d(TAG, "Fix obtained: ${location.latitude},${location.longitude} " +
                    "acc=${location.accuracy}m age=${elapsedMs / 1000}s")

            val geo = reverseGeocode(location.latitude, location.longitude)

            lastResult = LocationResult(
                latitude       = location.latitude,
                longitude      = location.longitude,
                accuracyMeters = location.accuracy,
                timestampMs    = location.time,
                displayLabel   = geo.label,
                locality       = geo.locality,
                street         = geo.street,
                postcode       = geo.postcode,
                country        = geo.country,
                isFresh        = isFresh,
                isApproximate  = isApprox,
            )
            Log.d(TAG, "Location updated: ${geo.label} (fresh=$isFresh)")
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    @Suppress("MissingPermission")
    private suspend fun fetchCurrentLocation(highAccuracy: Boolean = false): android.location.Location? =
        withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val cts = CancellationTokenSource()
                cont.invokeOnCancellation { cts.cancel() }

                val priority = if (highAccuracy)
                    Priority.PRIORITY_HIGH_ACCURACY
                else
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY

                fusedClient.getCurrentLocation(priority, cts.token)
                    .addOnSuccessListener { loc ->
                        cont.resume(loc)
                    }.addOnFailureListener { e ->
                        Log.w(TAG, "getCurrentLocation failed: ${e.message}")
                        cont.resume(null)
                    }
            }
        }

    @Suppress("MissingPermission")
    private suspend fun fetchLastLocation(): android.location.Location? =
        suspendCancellableCoroutine { cont ->
            fusedClient.lastLocation
                .addOnSuccessListener { loc -> cont.resume(loc) }
                .addOnFailureListener { e ->
                    Log.w(TAG, "lastLocation failed: ${e.message}")
                    cont.resume(null)
                }
        }

    /**
     * Structured geocode result used by [refresh] to populate [LocationResult].
     * Carries the full [label], the short [locality], plus the individual
     * [street] and [postcode] fields needed by the live-location intent to
     * build "You're on X in Y" replies without re-parsing the label.
     */
    private data class GeocodeParts(
        val label: String?,
        val locality: String?,
        val street: String?,
        val postcode: String?,
        val country: String?
    )

    private fun reverseGeocode(lat: Double, lon: Double): GeocodeParts {
        return try {
            if (!Geocoder.isPresent()) return GeocodeParts(null, null, null, null, null)
            val geocoder = Geocoder(context, Locale.getDefault())

            val addresses = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                // API 33+: non-deprecated async API called synchronously via blocking latch
                var result: List<android.location.Address>? = null
                val latch = java.util.concurrent.CountDownLatch(1)
                geocoder.getFromLocation(lat, lon, 1) { addrs ->
                    result = addrs
                    latch.countDown()
                }
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                result
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lon, 1)
            }

            val addr = addresses?.firstOrNull() ?: return GeocodeParts(null, null, null, null, null)

            // Build label from most-specific to least-specific, deduplicating adjacent values.
            // subAdminArea (county/district) fills the gap when locality (city) is missing —
            // common in rural areas where the Geocoder only returns region + country.
            val candidates = listOfNotNull(
                addr.thoroughfare?.let { t ->
                    // Include street number if present: "14 High Street"
                    listOfNotNull(addr.subThoroughfare, t).joinToString(" ")
                },
                addr.subLocality,   // neighbourhood
                addr.locality,      // city / town
                addr.subAdminArea,  // county / district (e.g. "Pembrokeshire")
                addr.adminArea,     // region / state  (e.g. "Wales")
                addr.countryName,
            )
            // Deduplicate consecutive identical values then take up to 3 parts
            val deduped = candidates.fold(mutableListOf<String>()) { acc, s ->
                if (acc.lastOrNull()?.equals(s, ignoreCase = true) != true) acc.add(s)
                acc
            }
            val label = deduped.take(3).joinToString(", ").ifBlank { null }

            // locality for the short name; fall back to subAdminArea if city unknown
            val locality = addr.locality ?: addr.subAdminArea
            GeocodeParts(
                label    = label,
                locality = locality,
                // Street — thoroughfare without the house number.  The live-location
                // intent wants "You're on High Street", not "You're on 14 High Street".
                street   = addr.thoroughfare,
                postcode = addr.postalCode,
                country  = addr.countryName
            )
        } catch (e: Exception) {
            Log.w(TAG, "Geocoding failed: ${e.message}")
            GeocodeParts(null, null, null, null, null)
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
}
