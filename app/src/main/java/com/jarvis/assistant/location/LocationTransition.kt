package com.jarvis.assistant.location

/**
 * LocationTransition — the change the [PlaceLearner] just detected.
 *
 * Volatile state held by an AppLocationContextSource: one entry is
 * surfaced to the proactive engine per tick via
 * `LocationContextSource.getPendingTransition`, then cleared by
 * `acknowledge` after dispatch (analogous to the notification source).
 *
 * @param kind           Entry (user arrived) or Exit (user left).
 * @param place          The [KnownPlace] touched.
 * @param placeKind      Classified kind at detection time.
 * @param occurredAtMs   Epoch ms when the transition was detected.
 */
data class LocationTransition(
    val kind: Kind,
    val place: KnownPlace,
    val placeKind: PlaceKind,
    val occurredAtMs: Long
) {
    enum class Kind { ARRIVED, LEFT }
}
