package com.jarvis.assistant.location

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar
import kotlin.math.cos

/**
 * PlaceLearner — lightweight clustering of recurring dwell locations.
 *
 * Records each observed location as a **fix**; promotes a run of nearby
 * fixes into a **dwell** once the dwell threshold is met; merges dwells
 * into **known places** using a simple distance-based greedy clustering.
 *
 * Storage is SharedPreferences only — a few dozen rows of lat/lon/visit
 * counters.  Coordinates are rounded to 4 decimal places (~11 m) before
 * storage so a log exfil can't recover precise home/work addresses.
 *
 * This implementation is deliberately coarse.  When the product needs
 * more precision we can upgrade to Room + a proper DBSCAN — the public
 * surface ([observe] + [classify] + [knownPlaces]) is stable.
 */
class PlaceLearner(
    context: Context,
    private val nowMsProvider: () -> Long = System::currentTimeMillis,
    private val radiusMeters: Double = 100.0,
    private val minDwellMs: Long = 10 * 60 * 1000L,
    private val minVisitsForKnown: Int = 3
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private data class Fix(val lat: Double, val lon: Double, val atMs: Long)

    /** Rolling window of recent fixes; capped. */
    private val recentFixes = ArrayDeque<Fix>()
    private val maxRecent = 16

    @Synchronized
    fun observe(lat: Double, lon: Double, atMs: Long = nowMsProvider()) {
        recentFixes.addFirst(Fix(lat, lon, atMs))
        while (recentFixes.size > maxRecent) recentFixes.removeLast()
        maybePromoteDwell()
    }

    /**
     * If the current fix plus the preceding fixes have all stayed within
     * [radiusMeters] for at least [minDwellMs], promote them to a dwell
     * at a known place (creating or updating one).
     */
    private fun maybePromoteDwell() {
        if (recentFixes.size < 2) return
        val newest = recentFixes.first()
        val cluster = recentFixes.takeWhile { distanceMeters(newest.lat, newest.lon, it.lat, it.lon) <= radiusMeters }
        if (cluster.size < 2) return
        val dwellMs = newest.atMs - cluster.last().atMs
        if (dwellMs < minDwellMs) return

        upsertKnownPlace(newest.lat, newest.lon, cluster.last().atMs, newest.atMs)
    }

    @Synchronized
    private fun upsertKnownPlace(lat: Double, lon: Double, dwellStartMs: Long, dwellEndMs: Long) {
        val places = readPlaces().toMutableList()
        val existingIdx = places.indexOfFirst {
            distanceMeters(it.latitude, it.longitude, lat, lon) <= radiusMeters
        }
        val hour = Calendar.getInstance().apply { timeInMillis = dwellEndMs }.get(Calendar.HOUR_OF_DAY)
        val nightDwell = if (hour in 22..23 || hour in 0..6) (dwellEndMs - dwellStartMs) else 0L
        val dayDwell = if (hour in 9..17) (dwellEndMs - dwellStartMs) else 0L

        if (existingIdx >= 0) {
            val old = places[existingIdx]
            val newLat = (old.latitude * old.visitCount + lat) / (old.visitCount + 1)
            val newLon = (old.longitude * old.visitCount + lon) / (old.visitCount + 1)
            places[existingIdx] = old.copy(
                latitude = round4(newLat),
                longitude = round4(newLon),
                visitCount = old.visitCount + 1,
                lastSeenAt = dwellEndMs,
                nightDwellMs = old.nightDwellMs + nightDwell,
                dayDwellMs = old.dayDwellMs + dayDwell
            )
        } else {
            places += KnownPlace(
                latitude = round4(lat),
                longitude = round4(lon),
                visitCount = 1,
                firstSeenAt = dwellStartMs,
                lastSeenAt = dwellEndMs,
                nightDwellMs = nightDwell,
                dayDwellMs = dayDwell
            )
        }
        writePlaces(places)
    }

    /** Returns all learned places, newest-last. */
    fun knownPlaces(): List<KnownPlace> = readPlaces()

    /** Classify a lat/lon into a known PlaceKind, or UNKNOWN if no match. */
    fun classify(lat: Double, lon: Double): PlaceKind {
        val places = readPlaces()
        val match = places.firstOrNull {
            distanceMeters(it.latitude, it.longitude, lat, lon) <= radiusMeters
        } ?: return PlaceKind.UNKNOWN
        if (match.visitCount < minVisitsForKnown) return PlaceKind.UNKNOWN
        return kindFor(match, places)
    }

    /**
     * HOME = place with the largest [KnownPlace.nightDwellMs] (22:00–06:00)
     * that's been visited ≥ minVisitsForKnown times.  Everything else qualifying
     * is [PlaceKind.KNOWN].  Work inference intentionally omitted in this MVP
     * — heuristics were too noisy to ship without a proper UX.
     */
    private fun kindFor(place: KnownPlace, all: List<KnownPlace>): PlaceKind {
        val homeCandidate = all
            .filter { it.visitCount >= minVisitsForKnown }
            .maxByOrNull { it.nightDwellMs }
        return if (homeCandidate != null &&
            homeCandidate.latitude == place.latitude &&
            homeCandidate.longitude == place.longitude &&
            homeCandidate.nightDwellMs > 0L
        ) PlaceKind.HOME else PlaceKind.KNOWN
    }

    @Synchronized
    fun clearAll() {
        prefs.edit().remove(KEY_PLACES).apply()
        recentFixes.clear()
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun readPlaces(): List<KnownPlace> {
        val raw = prefs.getString(KEY_PLACES, null) ?: return emptyList()
        return raw.split(RECORD_SEP).mapNotNull { record ->
            val parts = record.split(FIELD_SEP)
            if (parts.size < 7) return@mapNotNull null
            try {
                KnownPlace(
                    latitude = parts[0].toDouble(),
                    longitude = parts[1].toDouble(),
                    visitCount = parts[2].toInt(),
                    firstSeenAt = parts[3].toLong(),
                    lastSeenAt = parts[4].toLong(),
                    nightDwellMs = parts[5].toLong(),
                    dayDwellMs = parts[6].toLong()
                )
            } catch (_: Exception) { null }
        }
    }

    private fun writePlaces(places: List<KnownPlace>) {
        val serialised = places.joinToString(RECORD_SEP) { p ->
            listOf(p.latitude, p.longitude, p.visitCount, p.firstSeenAt, p.lastSeenAt,
                p.nightDwellMs, p.dayDwellMs).joinToString(FIELD_SEP)
        }
        prefs.edit().putString(KEY_PLACES, serialised).apply()
    }

    companion object {
        private const val PREFS_NAME = "jarvis_place_learner"
        private const val KEY_PLACES = "known_places"
        private const val FIELD_SEP = "|"
        private const val RECORD_SEP = "\n"

        fun round4(v: Double): Double = Math.round(v * 10_000.0) / 10_000.0

        /** Equirectangular approximation — fine for the < 10 km scales here. */
        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = (lat2 - lat1) * 111_320.0
            val dLon = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
            return kotlin.math.sqrt(dLat * dLat + dLon * dLon)
        }
    }
}

/** A place the user has returned to often enough to be named. */
data class KnownPlace(
    val latitude: Double,
    val longitude: Double,
    val visitCount: Int,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    /** Total dwell milliseconds accumulated between 22:00–06:00. */
    val nightDwellMs: Long,
    /** Total dwell milliseconds accumulated between 09:00–17:00. */
    val dayDwellMs: Long
)

enum class PlaceKind { HOME, KNOWN, UNKNOWN }
