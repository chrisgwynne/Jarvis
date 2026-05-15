package com.jarvis.assistant.ambient.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jarvis.assistant.ambient.AmbientEvent
import com.jarvis.assistant.ambient.AmbientEventType
import com.jarvis.assistant.ambient.AmbientLocationBucket

@Entity(
    tableName = "ambient_events",
    indices = [
        Index("timestampMs"),
        Index("type"),
        Index("locationBucket"),
    ]
)
data class AmbientEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val type: String,
    val timestampMs: Long,
    val source: String,
    val metadataJson: String,
    val locationBucket: String,
    val appPackage: String?,
    val confidence: Float,
) {
    fun toDomain(): AmbientEvent = AmbientEvent(
        id = id,
        type = AmbientEventType.valueOf(type),
        timestampMs = timestampMs,
        source = source,
        metadata = metadataJson.toMetadataMap(),
        locationBucket = AmbientLocationBucket.valueOf(locationBucket),
        appPackage = appPackage,
        confidence = confidence,
    )

    companion object {
        fun from(event: AmbientEvent): AmbientEventEntity = AmbientEventEntity(
            id = event.id,
            type = event.type.name,
            timestampMs = event.timestampMs,
            source = event.source,
            metadataJson = event.metadata.toJson(),
            locationBucket = event.locationBucket.name,
            appPackage = event.appPackage,
            confidence = event.confidence,
        )
    }
}

private fun Map<String, String>.toJson(): String = entries.joinToString(",", "{", "}") {
    "\"${it.key}\":\"${it.value}\""
}

private fun String.toMetadataMap(): Map<String, String> {
    if (this == "{}") return emptyMap()
    return try {
        val inner = trim('{', '}')
        inner.split(",").associate { pair ->
            val (k, v) = pair.split(":", limit = 2)
            k.trim('"') to v.trim('"')
        }
    } catch (_: Exception) {
        emptyMap()
    }
}
