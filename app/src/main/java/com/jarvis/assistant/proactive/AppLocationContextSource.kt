package com.jarvis.assistant.proactive

import android.util.Log
import com.jarvis.assistant.location.CurrentLocationProvider
import com.jarvis.assistant.location.KnownPlace
import com.jarvis.assistant.location.LocationTransition
import com.jarvis.assistant.location.PlaceKind
import com.jarvis.assistant.location.PlaceLearner

/**
 * AppLocationContextSource — drives [PlaceLearner] from the proactive tick
 * and exposes the detected [LocationTransition] to the engine.
 *
 * Behaviour:
 *   1. On each [getPendingTransition] call, refresh the fused location.
 *   2. Feed the fix to [PlaceLearner.observe].
 *   3. Classify the current fix; compare to the last classified place.
 *   4. If it changed, store a transition for the next tick to surface.
 *
 * Transitions are held in a single volatile slot — if two transitions
 * happen in the same polling interval (rare), the newer one wins.
 * `acknowledge` clears the slot once the engine has dispatched.
 */
class AppLocationContextSource(
    private val locationProvider: CurrentLocationProvider,
    private val placeLearner: PlaceLearner,
    private val nowMsProvider: () -> Long = System::currentTimeMillis
) : LocationContextSource {

    companion object {
        private const val TAG = "AppLocationSource"
    }

    @Volatile private var lastKnownPlace: KnownPlace? = null
    @Volatile private var lastKnownKind: PlaceKind = PlaceKind.UNKNOWN
    @Volatile private var pending: LocationTransition? = null

    override suspend fun getPendingTransition(): LocationTransition? {
        try {
            locationProvider.refresh(highAccuracy = false)
        } catch (e: Exception) {
            Log.w(TAG, "Location refresh failed: ${e.message}")
            return pending
        }

        val fix = locationProvider.lastResult ?: return pending
        val now = nowMsProvider()
        placeLearner.observe(fix.latitude, fix.longitude, now)

        val kind = placeLearner.classify(fix.latitude, fix.longitude)
        val matched = placeLearner.knownPlaces().firstOrNull { p ->
            PlaceLearner.distanceMeters(p.latitude, p.longitude, fix.latitude, fix.longitude) <= 100.0
        }

        detectTransition(matched, kind, now)
        lastKnownPlace = matched
        lastKnownKind = kind
        return pending
    }

    override fun acknowledge() {
        pending = null
    }

    private fun detectTransition(current: KnownPlace?, currentKind: PlaceKind, nowMs: Long) {
        val prev = lastKnownPlace

        // Left: we used to be at a known place, now we're not (or we're at a different one).
        if (prev != null &&
            (current == null ||
                current.latitude != prev.latitude ||
                current.longitude != prev.longitude)
        ) {
            pending = LocationTransition(
                kind = LocationTransition.Kind.LEFT,
                place = prev,
                placeKind = lastKnownKind,
                occurredAtMs = nowMs
            )
            return
        }

        // Arrived: we weren't at a known place, now we are.
        if (prev == null && current != null && currentKind != PlaceKind.UNKNOWN) {
            pending = LocationTransition(
                kind = LocationTransition.Kind.ARRIVED,
                place = current,
                placeKind = currentKind,
                occurredAtMs = nowMs
            )
        }
    }
}
