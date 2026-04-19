package com.jarvis.assistant.maps

import android.util.Log
import com.jarvis.assistant.location.CurrentLocationProvider

/**
 * MapsCommandRouter — single entry point used by the maps tools so each tool
 * stays a thin matcher that forwards to one method here.
 *
 * Three behaviours, one per public method:
 *
 *   * [handleNearest]   → find the nearest place of a category and speak it.
 *   * [handleDirections] → resolve a destination, fetch a short route summary
 *                          and indicate that Maps should be opened.
 *   * [handleNavigate]  → speak a one-line ack and indicate that Maps nav
 *                          should be opened immediately (no summary fetch).
 *
 * The router owns the failure phrasing for the maps subsystem so the three
 * tools above never duplicate the "no location"/"no key"/"no match" copy.
 */
class MapsCommandRouter(
    private val locationProvider: CurrentLocationProvider,
    private val places: PlacesSearchCoordinator,
    private val directions: DirectionsCoordinator,
    private val intents: MapsIntentHandler
) {

    companion object { private const val TAG = "MapsCommandRouter" }

    /** "What's the nearest <category>?" */
    suspend fun handleNearest(category: String): MapsResult {
        if (category.isBlank()) return MapsResult.failed("What kind of place?", MapsResult.ErrorCode.NO_MATCH)

        val loc = ensureFreshLocation() ?: return MapsResult.needsLocation()

        val match = places.findNearest(category, loc.latitude, loc.longitude)
        if (match == null) {
            return MapsResult.failed(
                spoken = "I couldn't find a nearby ${category.trim()}.",
                code   = MapsResult.ErrorCode.NO_MATCH
            )
        }

        val spoken = DirectionsSpeechFormatter.formatNearest(category.trim(), match)
        return MapsResult.ok(spoken, place = match)
    }

    /** "How do I get to X?" / "Give directions to X" */
    suspend fun handleDirections(destination: String, mode: TravelMode = TravelMode.DRIVING): MapsResult {
        if (destination.isBlank()) {
            return MapsResult.failed("Where to?", MapsResult.ErrorCode.NO_MATCH)
        }

        val loc = locationProvider.lastResult   // may be null — Distance Matrix needs origin
        // Try to resolve the destination to a place near the user first so
        // the spoken summary uses the matched POI name ("Tesco Express, …")
        // rather than the raw transcript.
        val place = places.findByName(destination, loc?.latitude, loc?.longitude)

        val route = directions.summarise(
            destinationText  = destination,
            destinationPlace = place,
            originLat        = loc?.latitude,
            originLng        = loc?.longitude,
            mode             = mode
        )

        val opening = intents.open(route.mapsIntentUri)
        if (!opening) {
            return MapsResult(
                status         = MapsResult.Status.FAILED,
                spokenSummary  = "I can't open Google Maps on this phone.",
                routeSummary   = route,
                shouldOpenMaps = false,
                errorCode      = MapsResult.ErrorCode.MAPS_NOT_INSTALLED
            )
        }

        return MapsResult.ok(
            spoken    = DirectionsSpeechFormatter.formatRouteWithOpen(route, openingMaps = true),
            place     = place,
            route     = route,
            openMaps  = true
        )
    }

    /** "Take me to X" / "Navigate to X" — fastest path: open nav, speak ack. */
    suspend fun handleNavigate(destination: String, mode: TravelMode = TravelMode.DRIVING): MapsResult {
        if (destination.isBlank()) {
            return MapsResult.failed("Where to?", MapsResult.ErrorCode.NO_MATCH)
        }

        val loc = locationProvider.lastResult
        // Best-effort place lookup so the navigation intent gets coordinates
        // (Maps navigates more reliably with lat,lng than free-text on rural
        // destinations).  If Places isn't configured, fall through to text.
        val place = places.findByName(destination, loc?.latitude, loc?.longitude)

        val navUri = if (place != null)
            intents.navigationUri(place.latitude, place.longitude, mode)
        else
            intents.navigationUri(destination, mode)

        val opened = intents.open(navUri)
        if (!opened) {
            // Fall back to the universal directions URL — opens in a browser
            // when Google Maps is missing/disabled.
            val fallback = intents.directionsUri(destination, mode)
            if (!intents.open(fallback)) {
                return MapsResult.failed(
                    spoken = "I can't open Google Maps on this phone.",
                    code   = MapsResult.ErrorCode.MAPS_NOT_INSTALLED
                )
            }
        }

        val display = place?.name ?: destination.trim()
        return MapsResult.ok(
            spoken   = DirectionsSpeechFormatter.formatNavigateAck(display),
            place    = place,
            openMaps = true
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Returns a fresh location, refreshing once when [CurrentLocationProvider.lastResult]
     * is null or stale.  Returns null only when the provider can't get one
     * (no permission, no fix, timeout) — caller speaks the no-location line.
     */
    private suspend fun ensureFreshLocation(): com.jarvis.assistant.location.LocationResult? {
        locationProvider.lastResult?.takeIf { it.isFresh }?.let { return it }
        try { locationProvider.refresh() } catch (e: Exception) {
            Log.w(TAG, "refresh failed: ${e.message}")
        }
        return locationProvider.lastResult
    }
}
