package com.jarvis.assistant.knowledge.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "wiki_pages",
    indices = [
        Index("pageType"),
        Index(value = ["titleNormalized"], unique = true),
        Index("status"),
        Index("updatedAt")
    ]
)
data class WikiPage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pageType: String,
    val title: String,
    val titleNormalized: String,
    val summary: String = "",
    val body: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val confidence: Float = 0.5f,
    val status: String = ACTIVE,
    val sourceCount: Int = 0
) {
    companion object {
        const val PERSON        = "PERSON"
        const val PLACE         = "PLACE"
        const val PROJECT       = "PROJECT"
        const val TOPIC         = "TOPIC"
        const val DAILY_SUMMARY = "DAILY_SUMMARY"
        const val TIMELINE      = "TIMELINE"

        const val ACTIVE   = "ACTIVE"
        const val STALE    = "STALE"
        const val ARCHIVED = "ARCHIVED"
    }
}
