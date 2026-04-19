package com.jarvis.assistant.maps

import android.net.Uri

/**
 * MapsResult — single shape returned by every maps coordinator path so the
 * callers (NearestPlaceTool, DirectionsTool, NavigateTool) only ever have
 * one thing to inspect.
 *
 * @property status         What happened — drives whether the caller speaks
 *                          [spokenSummary], opens an intent, or surfaces a
 *                          failure.
 * @property spokenSummary  One short sentence ready for TTS (≤ 25 words).
 * @property placeMatch     Populated for nearest-place results.
 * @property routeSummary   Populated for directions results.
 * @property shouldOpenMaps If true, the caller should fire
 *                          [routeSummary?.navigationIntentUri] /
 *                          [routeSummary?.mapsIntentUri] before returning.
 * @property errorCode      Stable enum for tests / metrics; null on success.
 */
data class MapsResult(
    val status: Status,
    val spokenSummary: String,
    val placeMatch: PlaceMatch? = null,
    val routeSummary: RouteSummary? = null,
    val shouldOpenMaps: Boolean = false,
    val errorCode: ErrorCode? = null
) {
    enum class Status { OK, FAILED, AMBIGUOUS, NEEDS_LOCATION }

    enum class ErrorCode {
        NO_LOCATION,
        NO_MATCH,
        MAPS_NOT_INSTALLED,
        API_FAILURE,
        NO_API_KEY,
        AMBIGUOUS_DESTINATION
    }

    companion object {
        fun ok(spoken: String, place: PlaceMatch? = null, route: RouteSummary? = null,
               openMaps: Boolean = false): MapsResult =
            MapsResult(Status.OK, spoken, place, route, openMaps)

        fun failed(spoken: String, code: ErrorCode): MapsResult =
            MapsResult(Status.FAILED, spoken, errorCode = code)

        fun needsLocation(spoken: String = "I can't get your location right now."): MapsResult =
            MapsResult(Status.NEEDS_LOCATION, spoken, errorCode = ErrorCode.NO_LOCATION)
    }
}

/**
 * PlaceMatch — minimum shape needed to speak a useful sentence and (if the
 * user follows up) open Google Maps to the right place.
 */
data class PlaceMatch(
    val name: String,
    val address: String?,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Double?,
    val travelTimeText: String? = null,
    val isOpen: Boolean? = null,
    val placeId: String? = null
) {
    /** Maps `geo:` URI ready to hand off to Google Maps for view-only display. */
    val viewUri: Uri
        get() = Uri.parse("geo:$latitude,$longitude?q=" + Uri.encode("$name${address?.let { ", $it" } ?: ""}"))
}

/**
 * RouteSummary — short distance/ETA + ready-to-fire Maps URIs.
 *
 * The routing backend may not be available (no API key, offline, API failure),
 * in which case [distanceText] and [durationText] are null but the URIs are
 * still populated so a "navigate to X" command can hand off blind.
 */
data class RouteSummary(
    val destinationName: String,
    val destinationAddress: String? = null,
    val destinationLat: Double? = null,
    val destinationLng: Double? = null,
    val distanceText: String?,
    val durationText: String?,
    val mode: TravelMode = TravelMode.DRIVING,
    val mapsIntentUri: Uri,
    val navigationIntentUri: Uri? = null
)

enum class TravelMode(val mapsValue: String) {
    DRIVING("driving"),
    WALKING("walking"),
    BICYCLING("bicycling"),
    TRANSIT("transit");

    companion object {
        fun fromText(s: String?): TravelMode = when (s?.lowercase()) {
            "walk", "walking", "on foot" -> WALKING
            "bike", "bicycle", "cycling" -> BICYCLING
            "bus", "train", "transit", "public transport" -> TRANSIT
            else -> DRIVING
        }
    }
}
