package com.jarvis.assistant.followup

/**
 * EntityReference — a named entity that was mentioned during the current
 * conversation and can be referred to in follow-up turns.
 *
 * Salience represents how "front of mind" the entity currently is; it starts
 * at 1.0 and decays toward 0 as turns pass.
 */
data class EntityReference(
    val entityType: EntityType,
    val entityId: String? = null,      // canonical id if known (e.g. contact URI)
    val label: String,                 // how to speak about this entity ("Chris")
    val aliases: List<String> = emptyList(),
    val confidence: Float = 1.0f,
    var salience: Float = 1.0f,
    val sourceTurnId: String = "",
    var lastSeenAt: Long = System.currentTimeMillis()
)

enum class EntityType {
    CONTACT,         // a person from the address book
    APP,             // a mobile application
    LOCATION,        // a place ("the office", "London")
    DEVICE,          // a smart-home device or sensor
    ROOM,            // a room name ("kitchen", "living room")
    MEDIA_ITEM,      // a song, playlist, podcast, etc.
    REMINDER_SUBJECT,// the topic of a reminder being composed
    SEARCH_TOPIC,    // something recently searched for
    TIME_REFERENCE   // a temporal anchor ("tomorrow", "next week")
}
