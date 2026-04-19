package com.jarvis.assistant.maps

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * MapsIntentHandler — builds and dispatches the small set of Google Maps
 * intents Jarvis needs.  Pure-handoff layer: never blocks, never queries
 * the network, just constructs a URI and starts it.
 *
 * Three flavours:
 *   * [searchUri]    — `geo:0,0?q=<query>`           — view a place / category
 *   * [directionsUri] — `https://www.google.com/maps/dir/?api=1&...`
 *                       — opens Maps to the directions screen for a destination
 *   * [navigationUri] — `google.navigation:q=<dest>` — turn-by-turn nav mode
 *
 * Uses Google Maps when installed via setPackage("com.google.android.apps.maps").
 * Falls back to whatever activity handles the URI (browser, alternative maps
 * app) when Google Maps isn't present.  Returns false on dispatch so callers
 * can speak a clean failure rather than crashing.
 */
class MapsIntentHandler(private val context: Context) {

    companion object {
        private const val TAG = "MapsIntentHandler"
        const val MAPS_PACKAGE = "com.google.android.apps.maps"

        /** True when Google Maps is installed (and not disabled). */
        fun isGoogleMapsInstalled(context: Context): Boolean = try {
            context.packageManager.getApplicationInfo(MAPS_PACKAGE, 0).enabled
        } catch (_: Exception) { false }
    }

    /** `geo:` URI for opening a place by name or coordinates. */
    fun searchUri(query: String): Uri =
        Uri.parse("geo:0,0?q=" + Uri.encode(query.trim()))

    /** Universal directions URL — works in Google Maps app + as a browser fallback. */
    fun directionsUri(
        destination: String,
        mode: TravelMode = TravelMode.DRIVING,
        originLat: Double? = null,
        originLng: Double? = null
    ): Uri {
        val sb = StringBuilder("https://www.google.com/maps/dir/?api=1")
            .append("&destination=").append(Uri.encode(destination.trim()))
            .append("&travelmode=").append(mode.mapsValue)
        if (originLat != null && originLng != null) {
            sb.append("&origin=").append("$originLat,$originLng")
        }
        return Uri.parse(sb.toString())
    }

    /** `google.navigation:` deep link → Google Maps opens straight into nav mode. */
    fun navigationUri(destination: String, mode: TravelMode = TravelMode.DRIVING): Uri =
        Uri.parse("google.navigation:q=" + Uri.encode(destination.trim()) + "&mode=${navMode(mode)}")

    fun navigationUri(lat: Double, lng: Double, mode: TravelMode = TravelMode.DRIVING): Uri =
        Uri.parse("google.navigation:q=$lat,$lng&mode=${navMode(mode)}")

    /** Open [uri] in Google Maps if installed, otherwise any handler. Returns false on no handler. */
    fun open(uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // Prefer Google Maps explicitly when present so a generic "open this geo:"
        // request doesn't bounce to whichever app last won the chooser.
        if (isGoogleMapsInstalled(context)) intent.setPackage(MAPS_PACKAGE)

        // Verify a handler exists before starting; resolveActivity returns null
        // when nothing can serve the intent (no Maps + no browser).
        if (intent.resolveActivity(context.packageManager) == null) {
            // Drop the package hint and try once more — covers the case where Maps
            // is disabled but a browser can still open the universal HTTPS URL.
            intent.setPackage(null)
            if (intent.resolveActivity(context.packageManager) == null) {
                Log.w(TAG, "No activity to handle $uri")
                return false
            }
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open $uri: ${e.message}")
            false
        }
    }

    private fun navMode(mode: TravelMode): String = when (mode) {
        TravelMode.DRIVING    -> "d"
        TravelMode.WALKING    -> "w"
        TravelMode.BICYCLING  -> "b"
        TravelMode.TRANSIT    -> "l"
    }
}
