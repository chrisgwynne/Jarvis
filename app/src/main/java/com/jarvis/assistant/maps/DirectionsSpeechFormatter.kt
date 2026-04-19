package com.jarvis.assistant.maps

/**
 * DirectionsSpeechFormatter — turns a [PlaceMatch] / [RouteSummary] into the
 * single short sentence Jarvis actually speaks.
 *
 * Voice rules (per CLAUDE.md): one sentence, no preamble, no markdown,
 * confident tone.  Keep this file the only place those phrasings live so a
 * single edit retunes every maps-driven reply.
 */
object DirectionsSpeechFormatter {

    /** "The nearest pharmacy is Boots, 0.6 miles away." */
    fun formatNearest(category: String, place: PlaceMatch): String {
        val name = place.name
        val distance = formatDistance(place.distanceMeters)
        val time = place.travelTimeText?.let { ", about $it away" } ?: ""
        val openNote = when (place.isOpen) {
            true  -> ""                   // open is the unstated default
            false -> " It's closed right now."
            null  -> ""
        }
        // Distance fallback: when both distance and time are missing, drop the
        // distance fragment entirely rather than say "is Boots, away".
        val tail = when {
            time.isNotBlank()    -> time
            distance != null     -> ", $distance away"
            else                 -> ""
        }
        return "The nearest $category is $name$tail.$openNote".trim()
    }

    /** "Tesco is 12 minutes away by car. Opening directions now." */
    fun formatRouteWithOpen(route: RouteSummary, openingMaps: Boolean): String {
        val core = formatRoute(route)
        val tail = if (openingMaps) " Opening directions now." else ""
        return "$core$tail"
    }

    /** "Tesco is 12 minutes away by car." */
    fun formatRoute(route: RouteSummary): String {
        val name = route.destinationName
        val mode = when (route.mode) {
            TravelMode.DRIVING   -> "by car"
            TravelMode.WALKING   -> "on foot"
            TravelMode.BICYCLING -> "by bike"
            TravelMode.TRANSIT   -> "by transit"
        }
        return when {
            route.durationText != null && route.distanceText != null ->
                "$name is ${route.durationText} away $mode, ${route.distanceText}."
            route.durationText != null ->
                "$name is ${route.durationText} away $mode."
            route.distanceText != null ->
                "$name is ${route.distanceText} away."
            else ->
                "$name is straight ahead — opening directions."
        }
    }

    /** "Opening navigation to Manchester Airport." */
    fun formatNavigateAck(destination: String): String =
        "Opening navigation to $destination."

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** UK-friendly default: ≤ 1500 m → metres, otherwise miles. */
    fun formatDistance(meters: Double?): String? {
        if (meters == null || meters <= 0) return null
        return when {
            meters < 1000 -> "${meters.toInt()} metres"
            meters < 1_500 -> {
                // Round to the nearest 100 m for naturalness ("1.2 km").
                val km = (meters / 100).toInt() / 10.0
                "%.1f km".format(km)
            }
            else -> {
                val miles = meters / 1_609.34
                "%.1f miles".format(miles)
            }
        }
    }
}
