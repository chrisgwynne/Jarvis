package com.jarvis.assistant.knowledge.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "knowledge_sources",
    indices = [
        Index("createdAt"),
        Index("retentionClass"),
        Index("compiledAt")
    ]
)
data class KnowledgeSource(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceType: String,
    val createdAt: Long = System.currentTimeMillis(),
    val rawText: String,
    val metadataJson: String? = null,
    val retentionClass: String = TRANSIENT,
    val compiledAt: Long? = null
) {
    companion object {
        const val VOICE_TRANSCRIPT  = "VOICE_TRANSCRIPT"
        const val REMINDER          = "REMINDER"
        const val NOTE              = "NOTE"
        const val WEB_RESULT        = "WEB_RESULT"
        const val IMAGE_ANALYSIS    = "IMAGE_ANALYSIS"
        const val LOCATION_EVENT    = "LOCATION_EVENT"

        const val TRANSIENT   = "TRANSIENT"
        const val SHORT_TERM  = "SHORT_TERM"
        const val LONG_TERM   = "LONG_TERM"
    }
}
