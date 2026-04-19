package com.jarvis.assistant.location

/**
 * Snapshot of the device's current location, including a human-readable label
 * produced by reverse geocoding.
 *
 * @param latitude       WGS-84 latitude in decimal degrees.
 * @param longitude      WGS-84 longitude in decimal degrees.
 * @param accuracyMeters Horizontal accuracy radius in metres (68 % confidence), or null if unknown.
 * @param timestampMs    Epoch-millis when the fix was obtained.
 * @param displayLabel   Full reverse-geocoded label, e.g. "Shoreditch, London, United Kingdom".
 *                       Null if geocoding failed.
 * @param locality       City/suburb portion only, e.g. "London". Null if unavailable.
 * @param street         Thoroughfare (road/street name) only, e.g. "High Street". Used by the
 *                       live-location intent to build "You're on X" replies independently of
 *                       [displayLabel]'s truncation.
 * @param postcode       Postal code, e.g. "LL18 3AB". Null when the geocoder doesn't return one.
 * @param isFresh        True if the fix is recent enough to be trusted for prompt injection.
 * @param isApproximate  True if this is a coarse/network fix rather than a precise GPS fix.
 */
data class LocationResult(
    val latitude      : Double,
    val longitude     : Double,
    val accuracyMeters: Float?,
    val timestampMs   : Long,
    val displayLabel  : String?,
    val locality      : String?,
    val street        : String? = null,
    val postcode      : String? = null,
    val isFresh       : Boolean,
    val isApproximate : Boolean,
)
