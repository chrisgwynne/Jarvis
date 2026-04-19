package com.jarvis.assistant.maps

import android.util.Log
import com.jarvis.assistant.llm.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * PlacesSearchCoordinator — talks to the Google Places API (HTTP) to answer
 * two kinds of question:
 *
 *   * "nearest <category>"          → [findNearest] (Nearby Search ranked by distance)
 *   * "where is <named place>"      → [findByName]  (Text Search biased by current location)
 *
 * HTTP-only on purpose: keeps the integration to a single new dependency
 * (the API key in SettingsStore) instead of pulling in the full Places SDK.
 *
 * The API key is read from [keyProvider] on every call so the user can paste
 * one in via Settings without needing to restart the service.  No key →
 * structured null return, never a crash; the caller speaks "no key configured".
 *
 * Failures (no key, transport, JSON, no results) are returned as null and
 * logged — never thrown — because the only caller is a voice tool that
 * needs a clean failure spoken back, not an exception.
 */
class PlacesSearchCoordinator(
    /** Returns the current Google Maps Platform API key, or "" if unset. */
    private val keyProvider: () -> String
) {

    companion object {
        private const val TAG = "PlacesSearchCoordinator"
        private const val NEARBY_URL = "https://maps.googleapis.com/maps/api/place/nearbysearch/json"
        private const val TEXT_URL   = "https://maps.googleapis.com/maps/api/place/textsearch/json"
        /**
         * Default radius for nearby search.  Maps Platform requires either
         * `radius` or `rankby=distance`; with rankby=distance the API ranks
         * up to 20 results regardless and ignores radius.  Keep both for
         * compatibility with the rare `type` queries that need radius.
         */
        private const val DEFAULT_RADIUS_M = 5_000

        /**
         * Mapping of common spoken category words to Google Place "type"
         * filters.  Hitting a known type returns much better results than
         * a free-text Nearby search ("petrol station" → "gas_station" gets
         * just stations; the text path can return supermarket forecourts).
         */
        private val CATEGORY_TYPES = mapOf(
            "petrol station"  to "gas_station",
            "petrol"          to "gas_station",
            "gas station"     to "gas_station",
            "fuel"            to "gas_station",
            "pharmacy"        to "pharmacy",
            "chemist"         to "pharmacy",
            "hospital"        to "hospital",
            "a and e"         to "hospital",
            "a&e"             to "hospital",
            "doctor"          to "doctor",
            "gp"              to "doctor",
            "supermarket"     to "supermarket",
            "grocery"         to "supermarket",
            "coffee shop"     to "cafe",
            "coffee"          to "cafe",
            "cafe"            to "cafe",
            "restaurant"      to "restaurant",
            "pub"             to "bar",
            "bar"             to "bar",
            "atm"             to "atm",
            "cash machine"    to "atm",
            "bank"            to "bank",
            "post office"     to "post_office",
            "police station"  to "police",
            "police"          to "police",
            "petrol garage"   to "gas_station",
            "gym"             to "gym",
            "park"            to "park",
            "school"          to "school",
            "hotel"           to "lodging",
            "train station"   to "train_station",
            "bus stop"        to "bus_station",
            "airport"         to "airport"
        )

        /** True when [query] looks like a category we have a structured Google type for. */
        fun knownCategoryType(query: String): String? {
            val lower = query.lowercase().trim()
            return CATEGORY_TYPES[lower]
                ?: CATEGORY_TYPES.entries.firstOrNull { lower.contains(it.key) }?.value
        }
    }

    /**
     * Rank-by-distance nearby search around (lat, lng) for [category].
     *
     * Uses Google's Place type filter when [category] maps to one
     * ([CATEGORY_TYPES]); otherwise uses the keyword field with the raw
     * spoken category.  Returns null on any failure / empty result so the
     * caller can speak "I couldn't find a nearby <category>".
     */
    suspend fun findNearest(
        category: String,
        lat: Double,
        lng: Double,
        openNow: Boolean = false
    ): PlaceMatch? = withContext(Dispatchers.IO) {
        val key = keyProvider().trim()
        if (key.isEmpty()) {
            Log.w(TAG, "Google Maps API key not configured")
            return@withContext null
        }

        val type = knownCategoryType(category)
        val url = buildString {
            append(NEARBY_URL)
            append("?location=$lat,$lng")
            append("&rankby=distance")
            // rankby=distance requires either type or keyword; never both empty.
            if (type != null) append("&type=$type")
            else append("&keyword=").append(java.net.URLEncoder.encode(category, "UTF-8"))
            if (openNow) append("&opennow=true")
            append("&key=").append(java.net.URLEncoder.encode(key, "UTF-8"))
        }

        val response = safeGet(url) ?: return@withContext null
        val body = NetworkClient.gson.fromJson(response, NearbyResponse::class.java)
        if (body == null || body.status != "OK" || body.results.isNullOrEmpty()) {
            Log.d(TAG, "Nearby returned status=${body?.status} results=${body?.results?.size}")
            return@withContext null
        }
        val first = body.results.first()
        toPlaceMatch(first, originLat = lat, originLng = lng)
    }

    /**
     * Text search biased toward (lat, lng) — used when the user names a place
     * directly ("how do I get to Tesco?").  Returns the top result.
     */
    suspend fun findByName(
        query: String,
        lat: Double?,
        lng: Double?
    ): PlaceMatch? = withContext(Dispatchers.IO) {
        val key = keyProvider().trim()
        if (key.isEmpty()) return@withContext null

        val url = buildString {
            append(TEXT_URL)
            append("?query=").append(java.net.URLEncoder.encode(query, "UTF-8"))
            if (lat != null && lng != null) {
                append("&location=$lat,$lng")
                append("&radius=$DEFAULT_RADIUS_M")
            }
            append("&key=").append(java.net.URLEncoder.encode(key, "UTF-8"))
        }

        val response = safeGet(url) ?: return@withContext null
        val body = NetworkClient.gson.fromJson(response, NearbyResponse::class.java)
        if (body == null || body.status != "OK" || body.results.isNullOrEmpty()) {
            Log.d(TAG, "Text search status=${body?.status} for \"$query\"")
            return@withContext null
        }
        val first = body.results.first()
        toPlaceMatch(first, originLat = lat, originLng = lng)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private suspend fun safeGet(url: String): String? = try {
        NetworkClient.get(url)
    } catch (e: IOException) {
        Log.w(TAG, "Network error: ${e.message}")
        null
    } catch (e: Exception) {
        Log.w(TAG, "Places call failed: ${e.message}")
        null
    }

    private fun toPlaceMatch(r: PlaceResult, originLat: Double?, originLng: Double?): PlaceMatch? {
        val loc = r.geometry?.location ?: return null
        val distance = if (originLat != null && originLng != null) {
            haversine(originLat, originLng, loc.lat, loc.lng)
        } else null
        return PlaceMatch(
            name           = r.name ?: return null,
            address        = r.vicinity ?: r.formatted_address,
            latitude       = loc.lat,
            longitude      = loc.lng,
            distanceMeters = distance,
            isOpen         = r.opening_hours?.open_now,
            placeId        = r.place_id
        )
    }

    /** Great-circle distance in metres — good enough for "x metres away" speech. */
    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    // ── Wire shapes ──────────────────────────────────────────────────────────

    private data class NearbyResponse(
        val status: String?,
        val results: List<PlaceResult>?,
        val error_message: String?
    )

    private data class PlaceResult(
        val name: String?,
        val vicinity: String?,
        val formatted_address: String?,
        val place_id: String?,
        val geometry: Geometry?,
        val opening_hours: OpeningHours?
    )

    private data class Geometry(val location: LatLng?)
    private data class LatLng(val lat: Double, val lng: Double)
    private data class OpeningHours(val open_now: Boolean?)
}
