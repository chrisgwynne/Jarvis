package com.jarvis.assistant.ambient

/**
 * Coarse location classification used throughout the ambient system.
 *
 * We deliberately keep this coarse so no exact location history is stored.
 * The bucket is derived from [com.jarvis.assistant.location.PlaceLearner]
 * transitions and SSID checks, never from raw GPS coordinates.
 */
enum class AmbientLocationBucket {
    HOME,
    WORK,
    SHOP,
    TRANSIT,
    UNKNOWN,
}
