package com.jarvis.assistant.followup

/**
 * SlotValue — a filled slot with the original raw string, a normalised form,
 * a confidence score, and the timestamp at which it was collected.
 */
data class SlotValue(
    val raw: String,
    val normalized: String = raw,
    val confidence: Float = 1.0f,
    val updatedAt: Long = System.currentTimeMillis()
)
