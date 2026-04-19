package com.jarvis.assistant.maps

import android.net.Uri
import android.util.Log
import com.jarvis.assistant.llm.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URLEncoder

/**
 * DirectionsCoordinator — produces a [RouteSummary] for "how do I get to X".
 *
 * Two paths, in order:
 *
 *   1. If the destination is already a [PlaceMatch] (resolved by
 *      [PlacesSearchCoordinator]) we hit the Distance Matrix API for the
 *      origin → destination ETA + distance.  Cheap, one round-trip.
 *
 *   2. Otherwise we hand the raw destination text straight to Distance Matrix.
 *      The API resolves the destination string itself.
 *
 * Either way the [RouteSummary] always carries fully-formed Maps URIs so a
 * caller can hand off to navigation even when the API is offline / blocked.
 *
 * Failure mode: when no API key is configured or any request fails, returns
 * a [RouteSummary] with null distance/duration but populated URIs.  Callers
 * use the URIs to hand off; speech falls back to the destination name.
 */
class DirectionsCoordinator(
    private val keyProvider: () -> String,
    private val intentHandler: MapsIntentHandler
) {

    companion object {
        private const val TAG = "DirectionsCoordinator"
        private const val DISTANCE_MATRIX_URL =
            "https://maps.googleapis.com/maps/api/distancematrix/json"
    }

    /**
     * Compute a [RouteSummary] for the trip from (originLat, originLng) to
     * either [destinationPlace] or [destinationText].  At least one of those
     * must be supplied; the place takes precedence when both are present.
     */
    suspend fun summarise(
        destinationText: String?,
        destinationPlace: PlaceMatch? = null,
        originLat: Double?,
        originLng: Double?,
        mode: TravelMode = TravelMode.DRIVING
    ): RouteSummary {
        val destName = destinationPlace?.name ?: destinationText
            ?.takeIf { it.isNotBlank() }
            ?: error("DirectionsCoordinator.summarise: no destination supplied")

        // Build the universal URIs first so we always have something to hand
        // off — even if the Distance Matrix call fails or no key is set.
        val destForUri = destinationPlace?.let { p ->
            // Prefer lat,lng for precision; falls back to the name for text.
            "${p.latitude},${p.longitude}"
        } ?: destName

        val mapsUri = intentHandler.directionsUri(
            destination = destForUri,
            mode        = mode,
            originLat   = originLat,
            originLng   = originLng
        )
        val navUri = if (destinationPlace != null)
            intentHandler.navigationUri(destinationPlace.latitude, destinationPlace.longitude, mode)
        else
            intentHandler.navigationUri(destName, mode)

        val (distanceText, durationText) = fetchDistanceMatrix(
            originLat   = originLat,
            originLng   = originLng,
            destination = destForUri,
            mode        = mode
        )

        return RouteSummary(
            destinationName     = destName,
            destinationAddress  = destinationPlace?.address,
            destinationLat      = destinationPlace?.latitude,
            destinationLng      = destinationPlace?.longitude,
            distanceText        = distanceText,
            durationText        = durationText,
            mode                = mode,
            mapsIntentUri       = mapsUri,
            navigationIntentUri = navUri
        )
    }

    // ── Distance Matrix HTTP ─────────────────────────────────────────────────

    /** Returns Pair(distanceText, durationText) — both null on any failure. */
    private suspend fun fetchDistanceMatrix(
        originLat: Double?,
        originLng: Double?,
        destination: String,
        mode: TravelMode
    ): Pair<String?, String?> = withContext(Dispatchers.IO) {
        if (originLat == null || originLng == null) return@withContext Pair(null, null)
        val key = keyProvider().trim()
        if (key.isEmpty()) return@withContext Pair(null, null)

        val url = buildString {
            append(DISTANCE_MATRIX_URL)
            append("?origins=$originLat,$originLng")
            append("&destinations=").append(URLEncoder.encode(destination, "UTF-8"))
            append("&mode=").append(mode.mapsValue)
            append("&units=metric")
            append("&key=").append(key)
        }

        val response = try {
            NetworkClient.get(url)
        } catch (e: IOException) {
            Log.w(TAG, "Network error: ${e.message}"); return@withContext Pair(null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Distance matrix call failed: ${e.message}"); return@withContext Pair(null, null)
        }

        val parsed = try {
            NetworkClient.gson.fromJson(response, DistanceMatrixResponse::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Bad JSON: ${e.message}"); return@withContext Pair(null, null)
        }

        if (parsed?.status != "OK" || parsed.rows.isNullOrEmpty()) {
            Log.d(TAG, "Distance matrix status=${parsed?.status}")
            return@withContext Pair(null, null)
        }
        val element = parsed.rows.first().elements?.firstOrNull()
        if (element == null || element.status != "OK") {
            Log.d(TAG, "Element status=${element?.status}")
            return@withContext Pair(null, null)
        }
        Pair(element.distance?.text, element.duration?.text)
    }

    // ── Wire shapes ──────────────────────────────────────────────────────────

    private data class DistanceMatrixResponse(
        val status: String?,
        val rows: List<Row>?
    )
    private data class Row(val elements: List<Element>?)
    private data class Element(
        val status: String?,
        val distance: TextValue?,
        val duration: TextValue?
    )
    private data class TextValue(val text: String?, val value: Long?)

    /** Stub helper exposed for tests / UI: build a Maps intent for a raw [Uri]. */
    fun open(uri: Uri): Boolean = intentHandler.open(uri)
}
